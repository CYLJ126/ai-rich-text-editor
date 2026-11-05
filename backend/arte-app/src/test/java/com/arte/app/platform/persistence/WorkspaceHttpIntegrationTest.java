package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.arte.app.platform.identity.*;
import com.arte.app.platform.workspace.ReactiveTenantQueries;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.platform.workspace.TenantDirectoryBrowser;
import com.arte.app.platform.workspace.WorkspaceDirectoryBrowser;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.web.advice.GlobalExceptionHandler;
import com.arte.app.web.advice.PlatformExceptionHandler;
import com.arte.app.web.aspect.WebAspect;
import com.arte.app.web.controller.platform.TenantDirectoryController;
import com.arte.app.web.controller.platform.WorkspaceAccessController;
import com.arte.app.web.controller.platform.WorkspaceController;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.enums.workspace.*;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HTTP + 实际旧 Web 切面 + 真实身份／MyBatis／core 服务；默认使用隔离 H2。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:42 ✾
 */
class WorkspaceHttpIntegrationTest {
    private final PlatformPersistenceIntegrationTest.Cursors cursors = new PlatformPersistenceIntegrationTest.Cursors();
    private final PlatformPersistenceIntegrationTest fixture = new PlatformPersistenceIntegrationTest();
    private Scheduler scheduler;
    private LocalValidatorFactoryBean validator;
    private ReactiveWorkspaceOperations operations;
    private MockMvc mvc;
    private Authentication alice;

    @BeforeEach
    void setup() throws Exception {
        fixture.setup();
        var accounts = new RbacAccountReader(fixture.source,
                new RbacAccountMapperConfiguration().identityUserMapper(fixture.source));
        var identities = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(fixture.source, accounts),
                new TransactionTemplate(new DataSourceTransactionManager(fixture.source)),
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, Clock.systemUTC());
        scheduler = Schedulers.newBoundedElastic(2, 16, "workspace-http");
        var services = fixture.services;
        var executor = new PlatformPersistenceExecutor(scheduler, fixture.tx);
        operations = new ReactiveWorkspaceOperations(identities, executor,
                services.personal(), services.workspaces(), services.preferences(), services.scopes(), services.authorization(), services.writeGuard(), services.receiptQueries(), services.policies(),
                new WorkspaceDirectoryBrowser(services.workspaces(), cursors));
        var proxyFactory = new AspectJProxyFactory(new WorkspaceController(operations));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(new WebAspect());
        var accessProxy = new AspectJProxyFactory(new WorkspaceAccessController(operations));
        accessProxy.setProxyTargetClass(true);
        accessProxy.addAspect(new WebAspect());
        var tenantProxy = new AspectJProxyFactory(new TenantDirectoryController(
                new ReactiveTenantQueries(identities, executor, services.tenantQueries(),
                        new TenantDirectoryBrowser(services.tenantQueries(), cursors))));
        tenantProxy.setProxyTargetClass(true);
        tenantProxy.addAspect(new WebAspect());
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup((WorkspaceController) proxyFactory.getProxy(),
                        (WorkspaceAccessController) accessProxy.getProxy(), (TenantDirectoryController) tenantProxy.getProxy())
                .setControllerAdvice(new PlatformExceptionHandler(), new GlobalExceptionHandler())
                .setValidator(validator).setAsyncRequestTimeout(10000).build();
        alice = login(1, "alice");
    }

    @AfterEach
    void cleanup() {
        if (validator != null) validator.close();
        if (scheduler != null) scheduler.dispose();
        fixture.cleanup();
    }

    private Authentication login(long id, String name) {
        var online = new UserOnlineInfo().setId(id).setUserName(name).setAccountCreatedAt(fixture.jdbc.queryForObject(
                "SELECT create_time FROM arte_rbac_user WHERE id=?", (row, index) -> row.getObject(1, LocalDateTime.class), id));
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(online), null, List.of());
    }

    private MvcResult execute(String operation, String body, String key, Authentication authentication) throws Exception {
        String prefix = switch (operation) {
            case "checkWorkspaceAccess", "batchCheckWorkspaceAccess" -> "/workspace/access/";
            default -> "/workspace/";
        };
        MockHttpServletRequestBuilder request = post(prefix + operation).contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(body);
        if (key != null) request.header("Idempotency-Key", key);
        if (authentication != null) request.principal(authentication);
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(10000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result;
    }

    private JSONObject ok(String operation, String body, String key) throws Exception {
        var result = execute(operation, body, key, alice);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        var json = JSONUtil.parseObj(result.getResponse().getContentAsString());
        assertTrue(json.getBool("success"));
        return json;
    }

    private void rejected(String operation, String body, String key, Authentication auth, int status, String code) throws Exception {
        var result = execute(operation, body, key, auth);
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        var json = JSONUtil.parseObj(result.getResponse().getContentAsString());
        assertFalse(json.getBool("success"));
        assertEquals("PLATFORM_" + code, json.getStr("code"));
        assertNotNull(result.getResponse().getHeader("X-Trace-Id"));
    }

    private JSONObject initialize() throws Exception {
        return ok("initializePersonalWorkspace", null, "initialize").getJSONObject("data").getJSONObject("value");
    }

    private String renameBody(JSONObject scope, long version, String name) {
        return JSONUtil.createObj().set("scope", scope).set("expectedVersion", version)
                .set("name", name).set("description", "description").toString();
    }

    @Test
    void browseHttpUsesOpaqueContinuationAndIndependentVisibleCount() throws Exception {
        String tenant = initialize().getJSONObject("tenant").getStr("id");
        ok("createWorkspace", JSONUtil.createObj().set("tenantId", tenant).set("name", "First").toString(), "first");
        ok("createWorkspace", JSONUtil.createObj().set("tenantId", tenant).set("name", "Second").toString(), "second");
        var first = ok("browseAccessibleWorkspaces", "{\"size\":1}", null).getJSONObject("data");
        assertFalse(first.getBool("exhausted"));
        assertEquals(1, first.getJSONArray("records").size());
        String token = first.getStr("nextCursor");
        assertEquals(36, token.length());
        assertFalse(first.containsKey("total"));
        assertFalse(first.containsKey("nextBoundary"));
        rejected("browseAccessibleWorkspaces", JSONUtil.createObj().set("cursor", token).set("size", 2).toString(),
                null, alice, 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleWorkspaces", JSONUtil.createObj().set("cursor", token).set("size", 1).set("search", "other").toString(),
                null, alice, 400, "INVALID_ARGUMENT");
        var returned = first.getJSONArray("records").getJSONObject(0).getJSONObject("scope");
        fixture.jdbc.update("DELETE FROM arte_platform_workspace_member WHERE tenant_id=? AND workspace_id=?",
                returned.getStr("tenantId"), returned.getStr("workspaceId"));
        var next = ok("browseAccessibleWorkspaces", JSONUtil.createObj().set("cursor", token).set("size", 1).toString(), null).getJSONObject("data");
        assertNotEquals(returned, next.getJSONArray("records").getJSONObject(0).getJSONObject("scope"));
        assertEquals(2L, ok("countAccessibleWorkspaces", null, null).getLong("data"));
        cursors.values.remove(token);
        rejected("browseAccessibleWorkspaces", JSONUtil.createObj().set("cursor", token).set("size", 1).toString(),
                null, alice, 410, "CURSOR_EXPIRED");
        var missing = ok("browseAccessibleWorkspaces", "{\"search\":\"no such name\"}", null).getJSONObject("data");
        assertTrue(missing.getBool("exhausted"));
        assertEquals(0, missing.getJSONArray("records").size());
        rejected("browseAccessibleWorkspaces", "{\"cursor\":\"short\"}", null, alice, 400, "INVALID_ARGUMENT");
    }

    private void seedOrganization(String id, String name, TenantRoleEnum role, TenantStateEnum state,
                                  boolean memberCreateAllowed, boolean guestWorkspace) {
        String subject = fixture.actor.principal().subjectId();
        var policies = DefaultWorkspacePolicies.conservativePersonalDefaults();
        var policy = new TenantPolicy(id, policies.personalTenant(id).rules(), memberCreateAllowed, 1, 1, 0);
        fixture.tx.required(() -> {
            var created = Instant.parse("2026-10-01T00:00:00Z");
            fixture.preferences.lockSubject(subject);
            assertTrue(fixture.tenants.insert(new Tenant(id, name, TenantTypeEnum.ORGANIZATION, null, state,
                    subject, 0, 0, created, created)));
            fixture.tenants.lock(id).orElseThrow();
            if (role != null) assertTrue(fixture.tenantMembers.insert(new TenantMembership(
                    id + "-member", id, subject, role, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(fixture.policies.insertTenantPolicy(policy));
            if (guestWorkspace) {
                var scope = new Scope(id, id + "-workspace");
                var newer = Instant.parse("2026-10-30T00:00:00Z");
                assertTrue(fixture.workspaces.insert(new Workspace(scope, "Guest workspace", "",
                        WorkspaceStateEnum.ACTIVE, subject, 0, 0, newer, newer)));
                fixture.workspaces.lock(scope).orElseThrow();
                assertTrue(fixture.workspaceMembers.insert(new WorkspaceMembership(id + "-workspace-member", scope, subject,
                        WorkspaceRoleEnum.GUEST, MembershipStateEnum.ACTIVE, 0, 0)));
                assertTrue(fixture.policies.insertWorkspacePolicy(policies.workspace(scope, policy)));
            }
            return null;
        });
    }

    private static String tenantTarget(String id) {
        return JSONUtil.createObj().set("tenantId", id).toString();
    }

    @Test
    void independentTenantReadsUseCurrentFormalMembershipWithoutPersonalPreference() throws Exception {
        seedOrganization("a-member", "Alpha Team", TenantRoleEnum.MEMBER, TenantStateEnum.ACTIVE, false, false);
        seedOrganization("b-admin", "Beta Team", TenantRoleEnum.ADMIN, TenantStateEnum.ACTIVE, false, false);
        seedOrganization("c-guest", "Guest Team", TenantRoleEnum.GUEST, TenantStateEnum.ACTIVE, false, true);
        seedOrganization("d-inactive", "Inactive Team", TenantRoleEnum.OWNER, TenantStateEnum.SUSPENDED, false, false);
        seedOrganization("e-unrelated", "Unrelated Team", null, TenantStateEnum.ACTIVE, false, false);

        var first = ok("browseAccessibleTenants", "{\"size\":1,\"type\":1,\"search\":\" TEAM \"}", null).getJSONObject("data");
        assertEquals("a-member", first.getJSONArray("records").getJSONObject(0).getStr("id"));
        assertFalse(first.getBool("exhausted"));
        assertFalse(first.containsKey("total"));
        assertFalse(first.containsKey("nextBoundary"));
        String token = first.getStr("nextCursor");
        assertEquals(36, token.length());
        var nextBody = JSONUtil.createObj().set("cursor", token).set("size", 1).set("search", "team").set("type", 1);
        var next = ok("browseAccessibleTenants", nextBody.toString(), null).getJSONObject("data");
        assertEquals("b-admin", next.getJSONArray("records").getJSONObject(0).getStr("id"));
        var end = ok("browseAccessibleTenants", nextBody.set("cursor", next.getStr("nextCursor")).toString(), null)
                .getJSONObject("data");
        assertTrue(end.getBool("exhausted"));
        assertTrue(end.getJSONArray("records").isEmpty());
        rejected("browseAccessibleTenants", nextBody.set("cursor", token).set("type", 0).toString(),
                null, alice, 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleTenants", nextBody.set("type", 1).set("search", "changed").toString(),
                null, alice, 400, "INVALID_ARGUMENT");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)",
                java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        rejected("browseAccessibleTenants", nextBody.set("search", "team").toString(),
                null, login(2, "bob"), 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleWorkspaces", "{\"cursor\":\"" + token + "\"}", null, alice, 410, "CURSOR_EXPIRED");

        var member = ok("getTenant", tenantTarget("a-member"), null).getJSONObject("data");
        assertEquals(Set.of("id", "name", "type", "state", "currentRole", "version", "isPersonal", "viewLevel", "actions"), member.keySet());
        assertEquals(1, member.getInt("type"));
        assertEquals(2, member.getInt("currentRole"));
        assertFalse(member.getBool("isPersonal"));
        assertEquals("BASIC", member.getStr("viewLevel"));
        assertEquals(List.of("tenant:read"), member.getJSONArray("actions").toList(String.class));
        var admin = ok("getTenant", tenantTarget("b-admin"), null).getJSONObject("data");
        assertEquals("GOVERNANCE", admin.getStr("viewLevel"));
        assertEquals(List.of("tenant:read", "tenant:policy:write", "tenant:workspace:create"),
                admin.getJSONArray("actions").toList(String.class));
        for (var hidden : List.of("c-guest", "d-inactive", "e-unrelated", "not-present"))
            rejected("getTenant", tenantTarget(hidden), null, alice, 404, "NOT_FOUND");

        var guestSpaces = ok("browseAccessibleWorkspaces", tenantTarget("c-guest"), null).getJSONObject("data");
        assertEquals(1, guestSpaces.getJSONArray("records").size());
        assertEquals(3, guestSpaces.getJSONArray("records").getJSONObject(0).getInt("currentRole"));
        var tenantOnly = ok("browseAccessibleWorkspaces", tenantTarget("b-admin"), null).getJSONObject("data");
        assertTrue(tenantOnly.getJSONArray("records").isEmpty());
        assertTrue(tenantOnly.getBool("exhausted"));

        var options = ok("getTenantCreationOptions", "{}", null).getJSONObject("data");
        assertFalse(options.getBool("available"));
        assertFalse(options.getBool("organizationAllowed"));
        assertEquals("CAPABILITY_NOT_ENABLED", options.getStr("reason"));
        rejected("getTenantCreationOptions", "{\"subjectId\":\"other\"}", null, alice, 400, "INVALID_ARGUMENT");
        rejected("getTenantCreationOptions", "{}", null, null, 401, "UNAUTHENTICATED");
        rejected("getTenant", "{\"tenantId\":\"a-member\",\"currentRole\":0}", null, alice, 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleTenants", "{\"type\":2}", null, alice, 400, "INVALID_ARGUMENT");

        fixture.jdbc.update("UPDATE arte_platform_tenant_policy SET members_may_create_workspace=TRUE WHERE tenant_id='a-member'");
        assertEquals(List.of("tenant:read", "tenant:workspace:create"), ok("getTenant", tenantTarget("a-member"), null)
                .getJSONObject("data").getJSONArray("actions").toList(String.class));
        fixture.jdbc.update("UPDATE arte_platform_tenant_member SET state=1 WHERE tenant_id='b-admin'");
        var replay = ok("browseAccessibleTenants", nextBody.set("search", "team").toString(), null).getJSONObject("data");
        assertTrue(replay.getJSONArray("records").isEmpty());
        assertTrue(replay.getBool("exhausted"));
        rejected("getTenant", tenantTarget("b-admin"), null, alice, 404, "NOT_FOUND");

        fixture.jdbc.update("DELETE FROM arte_platform_tenant_policy WHERE tenant_id='a-member'");
        rejected("browseAccessibleTenants", "{}", null, alice, 503, "AUTHORITY_UNAVAILABLE");
        rejected("getTenant", tenantTarget("a-member"), null, alice, 503, "AUTHORITY_UNAVAILABLE");
        assertTrue(fixture.preferences.find(fixture.actor.principal().subjectId()).isEmpty());
        for (var table : List.of("command", "outbox", "audit")) assertEquals(0, fixture.count(table), table);
        assertEquals(5, fixture.count("tenant"));
    }

    @Test
    void workspaceTenantFilterIsAppliedBeforeLimitAndBoundToContinuation() throws Exception {
        String personal = initialize().getJSONObject("tenant").getStr("id");
        assertTrue(ok("getTenant", tenantTarget(personal), null).getJSONObject("data").getBool("isPersonal"));
        seedOrganization("org", "Organization", TenantRoleEnum.OWNER, TenantStateEnum.ACTIVE, false, false);
        // Without tenant filtering the latest two candidates are both outside the requested tenant.
        for (var name : List.of("Org first", "Org second"))
            ok("createWorkspace", JSONUtil.createObj().set("tenantId", "org").set("name", name).toString(), name.replace(' ', '-'));
        String subject = fixture.actor.principal().subjectId();
        assertEquals("org", fixture.workspaces.scanMembershipCandidates(subject, null, 1).records().getFirst().scope().tenantId());
        var restricted = fixture.workspaces.scanMembershipCandidates(subject, null, 1, personal);
        assertTrue(restricted.exhausted());
        assertEquals(personal, restricted.records().getFirst().scope().tenantId());

        var firstBody = JSONUtil.createObj().set("tenantId", "org").set("size", 1);
        var first = ok("browseAccessibleWorkspaces", firstBody.toString(), null).getJSONObject("data");
        assertFalse(first.getBool("exhausted"));
        assertEquals("org", first.getJSONArray("records").getJSONObject(0).getJSONObject("scope").getStr("tenantId"));
        String token = first.getStr("nextCursor");
        var second = ok("browseAccessibleWorkspaces", firstBody.set("cursor", token).toString(), null).getJSONObject("data");
        assertTrue(second.getBool("exhausted"));
        assertEquals("org", second.getJSONArray("records").getJSONObject(0).getJSONObject("scope").getStr("tenantId"));
        rejected("browseAccessibleWorkspaces", firstBody.set("tenantId", personal).toString(), null, alice, 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleWorkspaces", JSONUtil.createObj().set("size", 1).set("cursor", token).toString(),
                null, alice, 400, "INVALID_ARGUMENT");
        rejected("browseAccessibleTenants", JSONUtil.createObj().set("cursor", token).toString(),
                null, alice, 410, "CURSOR_EXPIRED");
    }

    @Test
    void policyHttpReadsWritesReplaysAndQueriesTypedReceipts() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        String tenantId = scope.getStr("tenantId");
        var target = JSONUtil.createObj().set("tenantId", tenantId);
        var tenantPolicy = ok("getTenantPolicy", target.toString(), null).getJSONObject("data").getJSONObject("policy");
        assertEquals(1L, tenantPolicy.getLong("personalTemplateVersion"));
        var rules = tenantPolicy.getJSONObject("rules");
        var tenantCommand = JSONUtil.createObj().set("tenantId", tenantId).set("expectedTenantVersion", 0)
                .set("expectedPolicyVersion", 0).set("rules", rules).set("membersMayCreateWorkspace", false);
        rejected("updateTenantPolicy", tenantCommand.toString(), null, alice, 400, "INVALID_ARGUMENT");
        var tenantReceipt = ok("updateTenantPolicy", tenantCommand.toString(), "tenant-policy").getJSONObject("data");
        assertEquals(1L, tenantReceipt.getJSONObject("value").getLong("version"));
        assertEquals(tenantReceipt.getStr("operationId"), ok("updateTenantPolicy", tenantCommand.toString(), "tenant-policy")
                .getJSONObject("data").getStr("operationId"));
        rejected("updateTenantPolicy", tenantCommand.toString(), "stale-tenant", alice, 409, "VERSION_CONFLICT");
        var workspaceTarget = JSONUtil.createObj().set("scope", scope);
        assertEquals(0L, ok("getWorkspacePolicy", workspaceTarget.toString(), null).getJSONObject("data").getJSONObject("policy").getLong("version"));
        var spaceCommand = JSONUtil.createObj().set("scope", scope).set("expectedWorkspaceVersion", 0)
                .set("expectedPolicyVersion", 0).set("rules", rules);
        var receipt = ok("updateWorkspacePolicy", spaceCommand.toString(), "workspace-policy").getJSONObject("data");
        assertEquals(1L, receipt.getJSONObject("value").getLong("version"));
        assertEquals(receipt.getStr("operationId"), ok("updateWorkspacePolicy", spaceCommand.toString(), "workspace-policy")
                .getJSONObject("data").getStr("operationId"));
        for (var saved : List.of(tenantReceipt, receipt)) {
            var queried = ok("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", saved.getStr("operationId")).toString(), null)
                    .getJSONObject("data");
            assertEquals(saved.getJSONObject("value"), queried.getJSONObject("value"));
        }
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT auth_epoch FROM arte_platform_tenant WHERE id=?", Long.class, tenantId));
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT auth_epoch FROM arte_platform_workspace WHERE id=?", Long.class, scope.getStr("workspaceId")));
        assertEquals(3, fixture.count("audit"));
        // Space ADMIN can read normal summaries but cannot read, update or replay full policy.
        fixture.jdbc.update("UPDATE arte_platform_workspace_member SET role=1,version=version+1 WHERE workspace_id=?", scope.getStr("workspaceId"));
        rejected("getWorkspacePolicy", workspaceTarget.toString(), null, alice, 403, "ACCESS_DENIED");
        rejected("updateWorkspacePolicy", spaceCommand.toString(), "workspace-policy", alice, 403, "ACCESS_DENIED");
        rejected("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", receipt.getStr("operationId")).toString(), null,
                alice, 403, "ACCESS_DENIED");
    }

    @Test
    void policyViewsReturnVerifiedTenantMetadataAndKeepGovernanceScopesIndependent() throws Exception {
        var initialized = initialize();
        var scope = initialized.getJSONObject("workspace").getJSONObject("scope");
        String tenantId = scope.getStr("tenantId");
        String target = JSONUtil.createObj().set("tenantId", tenantId).toString();
        String workspaceTarget = JSONUtil.createObj().set("scope", scope).toString();
        var tenant = ok("getTenantPolicy", target, null).getJSONObject("data");
        var workspace = ok("getWorkspacePolicy", workspaceTarget, null).getJSONObject("data");
        assertEquals(0, tenant.getInt("tenantType"));
        assertEquals(initialized.getJSONObject("tenant").getStr("name"), tenant.getStr("tenantName"));
        assertEquals(tenant.getStr("tenantName"), workspace.getStr("tenantName"));
        assertEquals(0, workspace.getInt("tenantType"));
        assertFalse(workspace.containsKey("tenantPolicy"));
        // 仅在隔离夹具中改变元数据，确认页面标签来自服务端，而不是租户 ID／默认空间猜测。
        fixture.jdbc.update("UPDATE arte_platform_tenant SET type=1,personal_subject_id=NULL,name='Policy organization' WHERE id=?", tenantId);
        assertEquals(1, ok("getTenantPolicy", target, null).getJSONObject("data").getInt("tenantType"));
        assertEquals("Policy organization", ok("getWorkspacePolicy", workspaceTarget, null).getJSONObject("data").getStr("tenantName"));
        fixture.jdbc.update("UPDATE arte_platform_tenant_member SET role=2,version=version+1 WHERE tenant_id=?", tenantId);
        rejected("getTenantPolicy", target, null, alice, 403, "ACCESS_DENIED");
        assertNotNull(ok("getWorkspacePolicy", workspaceTarget, null).getJSONObject("data").getJSONObject("policy"));
    }

    @Test
    void policyPageUpdatesKeepCompleteRulesAndEnforceTenantCeiling() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        String tenantId = scope.getStr("tenantId");
        var tenantView = ok("getTenantPolicy", JSONUtil.createObj().set("tenantId", tenantId).toString(), null).getJSONObject("data");
        var rules = tenantView.getJSONObject("policy").getJSONObject("rules");
        var creationTypes = rules.getJSONArray("allowedCreationTypes").toString();
        var exports = rules.getJSONArray("exportTypes").toString();
        rules.set("personalCredentialsAllowed", true).set("allowedProtocols", List.of("chat-completions-sse"))
                .set("allowedProviders", List.of("deepseek")).set("allowedDestinations", List.of("api.deepseek.com"));
        var workspaceTarget = JSONUtil.createObj().set("scope", scope);
        var spaceCommand = JSONUtil.createObj().set("scope", scope).set("expectedWorkspaceVersion", 0)
                .set("expectedPolicyVersion", 0).set("rules", rules);
        rejected("updateWorkspacePolicy", spaceCommand.toString(), "above-ceiling", alice, 403, "POLICY_DENIED");
        var tenantCommand = JSONUtil.createObj().set("tenantId", tenantId).set("expectedTenantVersion", tenantView.getLong("tenantVersion"))
                .set("expectedPolicyVersion", tenantView.getJSONObject("policy").getLong("version"))
                .set("membersMayCreateWorkspace", tenantView.getJSONObject("policy").getBool("membersMayCreateWorkspace")).set("rules", rules);
        rejected("updateTenantPolicy", JSONUtil.parseObj(tenantCommand.toString()).set("rolePresetVersion", 99).toString(), "forged-metadata", alice, 400, "INVALID_ARGUMENT");
        ok("updateTenantPolicy", tenantCommand.toString(), "page-tenant-policy");
        var beforeSpace = ok("getWorkspacePolicy", workspaceTarget.toString(), null).getJSONObject("data");
        assertEquals(1L, beforeSpace.getLong("tenantPolicyVersion"));
        assertTrue(beforeSpace.getJSONObject("policy").getJSONObject("rules").getJSONArray("allowedProviders").isEmpty());
        var result = ok("updateWorkspacePolicy", spaceCommand.toString(), "page-workspace-policy").getJSONObject("data").getJSONObject("value");
        assertEquals(creationTypes, result.getJSONObject("rules").getJSONArray("allowedCreationTypes").toString());
        assertEquals(exports, result.getJSONObject("rules").getJSONArray("exportTypes").toString());
        assertEquals(rules, result.getJSONObject("rules"));
        rejected("updateWorkspacePolicy", spaceCommand.toString(), "page-stale", alice, 409, "VERSION_CONFLICT");
        rejected("getTenantPolicy", JSONUtil.createObj().set("tenantId", tenantId).set("actor", "another-user").toString(), null, alice, 400, "INVALID_ARGUMENT");
    }

    @Test
    void previewProjectsOnlyWorkspaceRulesAndRejectsAChangedCeilingWithoutWriting() throws Exception {
        cn.hutool.json.JSONObject scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        String tenantId = scope.getStr("tenantId");
        String tenantTarget = JSONUtil.createObj().set("tenantId", tenantId).toString();
        String spaceTarget = JSONUtil.createObj().set("scope", scope).toString();
        cn.hutool.json.JSONObject initial = ok("getTenantPolicy", tenantTarget, null).getJSONObject("data");
        cn.hutool.json.JSONObject ceiling = initial.getJSONObject("policy").getJSONObject("rules");
        ceiling.set("allowedProviders", List.of("deepseek", "tenant-only-provider"))
                .set("allowedDestinations", List.of("api.deepseek.com", "private-catalog.example"));
        cn.hutool.json.JSONObject tenantUpdate = JSONUtil.createObj().set("tenantId", tenantId)
                .set("expectedTenantVersion", 0).set("expectedPolicyVersion", 0)
                .set("membersMayCreateWorkspace", false).set("rules", ceiling);
        ok("updateTenantPolicy", tenantUpdate.toString(), "allow-ceiling");
        cn.hutool.json.JSONObject current = ok("getWorkspacePolicy", spaceTarget, null).getJSONObject("data");
        assertTrue(current.getJSONObject("effect").getJSONObject("effective").getJSONArray("allowedProviders").isEmpty());
        cn.hutool.json.JSONObject rules = current.getJSONObject("policy").getJSONObject("rules");
        rules.set("allowedProviders", List.of("deepseek", "outside")).set("allowedDestinations", List.of("api.deepseek.com"));
        cn.hutool.json.JSONObject preview = JSONUtil.createObj().set("scope", scope)
                .set("expectedWorkspaceVersion", 0).set("expectedPolicyVersion", 0).set("expectedTenantPolicyVersion", 1).set("rules", rules);
        long receipts = fixture.count("command"), audits = fixture.count("audit"), events = fixture.count("outbox");
        cn.hutool.json.JSONObject projection = ok("previewWorkspacePolicy", preview.toString(), null).getJSONObject("data");
        assertEquals(1L, projection.getLong("tenantPolicyVersion"));
        assertEquals(List.of("allowedProviders"), projection.getJSONObject("proposed").getJSONArray("limitedFields").toList(String.class));
        assertEquals(List.of("deepseek"), projection.getJSONObject("proposed").getJSONObject("effective").getJSONArray("allowedProviders").toList(String.class));
        assertFalse(projection.toString().contains("tenant-only-provider"));
        assertFalse(projection.toString().contains("private-catalog.example"));
        assertEquals(receipts, fixture.count("command"));
        assertEquals(audits, fixture.count("audit"));
        assertEquals(events, fixture.count("outbox"));

        rules.set("allowedProviders", List.of("deepseek"));
        assertTrue(ok("previewWorkspacePolicy", preview.toString(), null).getJSONObject("data")
                .getJSONObject("proposed").getJSONArray("limitedFields").isEmpty());
        ok("updateWorkspacePolicy", preview.toString(), "save-previewed");
        ceiling.set("allowedProviders", List.of("tenant-only-provider"));
        tenantUpdate.set("expectedTenantVersion", 1).set("expectedPolicyVersion", 1);
        ok("updateTenantPolicy", tenantUpdate.toString(), "tighten-ceiling");
        fixture.jdbc.update("UPDATE arte_platform_tenant SET type=1,personal_subject_id=NULL WHERE id=?", tenantId);
        fixture.jdbc.update("UPDATE arte_platform_tenant_member SET role=2,version=version+1 WHERE tenant_id=?", tenantId);
        rejected("getTenantPolicy", tenantTarget, null, alice, 403, "ACCESS_DENIED");
        cn.hutool.json.JSONObject after = ok("getWorkspacePolicy", spaceTarget, null).getJSONObject("data");
        assertEquals(2L, after.getLong("tenantPolicyVersion"));
        assertEquals(List.of("deepseek"), after.getJSONObject("policy").getJSONObject("rules").getJSONArray("allowedProviders").toList(String.class));
        assertTrue(after.getJSONObject("effect").getJSONObject("effective").getJSONArray("allowedProviders").isEmpty());
        assertEquals(List.of("allowedProviders"), after.getJSONObject("effect").getJSONArray("limitedFields").toList(String.class));
        assertFalse(after.toString().contains("tenant-only-provider"));
        preview.set("expectedWorkspaceVersion", 1).set("expectedPolicyVersion", 1);
        rules.set("allowedProviders", List.of());
        rejected("previewWorkspacePolicy", preview.toString(), null, alice, 409, "VERSION_CONFLICT");
        rejected("updateWorkspacePolicy", preview.toString(), "stale-preview", alice, 409, "VERSION_CONFLICT");
        preview.set("expectedTenantPolicyVersion", 2);
        assertTrue(ok("previewWorkspacePolicy", preview.toString(), null).getJSONObject("data")
                .getJSONObject("proposed").getJSONArray("limitedFields").isEmpty());
        rules.set("allowedDestinations", List.of("https://api.deepseek.com/path"));
        rejected("previewWorkspacePolicy", preview.toString(), null, alice, 400, "INVALID_ARGUMENT");
    }

    @Test
    void policyHttpRejectsMissingVersionsMalformedRulesAndCrossTenantScope() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        String tenantId = scope.getStr("tenantId");
        var rules = ok("getTenantPolicy", JSONUtil.createObj().set("tenantId", tenantId).toString(), null)
                .getJSONObject("data").getJSONObject("policy").getJSONObject("rules");
        rejected("updateTenantPolicy", JSONUtil.createObj().set("tenantId", tenantId).set("rules", rules)
                .set("membersMayCreateWorkspace", false).toString(), "invalid", alice, 400, "INVALID_ARGUMENT");
        rejected("updateWorkspacePolicy", JSONUtil.createObj().set("scope", scope).set("expectedWorkspaceVersion", -1)
                .set("expectedPolicyVersion", 0).set("rules", rules).toString(), "negative", alice, 400, "INVALID_ARGUMENT");
        rejected("updateWorkspacePolicy", JSONUtil.createObj().set("scope", scope).set("expectedWorkspaceVersion", 0)
                .set("expectedPolicyVersion", 0).set("rules", JSONUtil.createObj()).toString(), "missing-rules", alice, 400, "INVALID_ARGUMENT");
        rejected("getTenantPolicy", "{\"tenantId\":\"another-tenant\"}", null, alice, 404, "NOT_FOUND");
        var cross = JSONUtil.createObj().set("tenantId", "another-tenant").set("workspaceId", scope.getStr("workspaceId"));
        rejected("getWorkspacePolicy", JSONUtil.createObj().set("scope", cross).toString(), null, alice, 404, "NOT_FOUND");
        assertEquals(1, fixture.count("audit"));
    }

    @Test
    void allSevenEndpointsCompleteWorkflowAndPreserveResponseShapes() throws Exception {
        var initialized = initialize();
        var defaultScope = initialized.getJSONObject("workspace").getJSONObject("scope");
        var created = ok("createWorkspace", JSONUtil.createObj().set("tenantId", defaultScope.getStr("tenantId"))
                .set("name", "Learning").set("description", "notes").toString(), "create").getJSONObject("data");
        var scope = created.getJSONObject("value").getJSONObject("scope");
        var page = ok("listAccessibleWorkspaces", "{}", null);
        assertEquals(1L, page.getLong("current"));
        assertEquals(20L, page.getLong("size"));
        assertEquals(2L, page.getLong("total"));
        assertEquals(2, page.getJSONArray("records").size());
        assertFalse(page.containsKey("data"));
        var secondPage = ok("listAccessibleWorkspaces", "{\"current\":2,\"size\":1}", null);
        assertEquals(2L, secondPage.getLong("current"));
        assertEquals(2L, secondPage.getLong("total"));
        assertEquals(1, secondPage.getJSONArray("records").size());
        var detail = ok("getWorkspace", JSONUtil.createObj().set("scope", scope).toString(), null).getJSONObject("data");
        assertEquals("Learning", detail.getStr("name"));
        var renamed = ok("renameWorkspace", renameBody(scope, created.getLong("resultVersion"), "Renamed"), "rename")
                .getJSONObject("data");
        assertEquals("Renamed", renamed.getJSONObject("value").getStr("name"));
        var preference = ok("getWorkspacePreference", null, null).getJSONObject("data");
        var update = JSONUtil.createObj().set("expectedPreferenceVersion", preference.getLong("version"))
                .set("startupScope", scope).set("lastScope", scope).set("favorites", List.of(scope));
        var updated = ok("updateWorkspacePreference", update.toString(), "preferences").getJSONObject("data").getJSONObject("value");
        assertEquals(defaultScope, updated.getJSONObject("defaultScope"));
        assertEquals(scope, updated.getJSONObject("startupScope"));
        assertEquals(1, updated.getJSONArray("favorites").size());
        var clear = JSONUtil.createObj().set("expectedPreferenceVersion", updated.getLong("version"))
                .set("startupScope", null).set("lastScope", null).set("favorites", List.of());
        var cleared = ok("updateWorkspacePreference", clear.toString(), "clear").getJSONObject("data").getJSONObject("value");
        assertTrue(JSONUtil.isNull(cleared.get("startupScope")));
        assertTrue(JSONUtil.isNull(cleared.get("lastScope")));
        assertEquals(0, cleared.getJSONArray("favorites").size());
        assertEquals(defaultScope, cleared.getJSONObject("defaultScope"));
        assertEquals(2, fixture.count("workspace"));
        assertEquals(fixture.count("outbox"), fixture.count("audit"));
    }

    @Test
    void initializationAndRenameReplayKeepOriginalOperationAndDoNotRepeatWrites() throws Exception {
        var first = ok("initializePersonalWorkspace", null, "first").getJSONObject("data");
        var replay = ok("initializePersonalWorkspace", null, "first").getJSONObject("data");
        assertEquals(first.getStr("operationId"), replay.getStr("operationId"));
        ok("initializePersonalWorkspace", null, "another-key");
        assertEquals(1, fixture.count("tenant"));
        assertEquals(1, fixture.count("workspace"));
        var scope = first.getJSONObject("value").getJSONObject("workspace").getJSONObject("scope");
        var body = renameBody(scope, 0, "Renamed");
        var rename = ok("renameWorkspace", body, "rename").getJSONObject("data");
        long auditCount = fixture.count("audit");
        assertEquals(rename.getStr("operationId"), ok("renameWorkspace", body, "rename").getJSONObject("data").getStr("operationId"));
        assertEquals(auditCount, fixture.count("audit"));
        rejected("renameWorkspace", renameBody(scope, 0, "Changed"), "rename", alice, 409, "IDEMPOTENCY_CONFLICT");
        rejected("renameWorkspace", renameBody(scope, 0, "Stale"), "new-key", alice, 409, "VERSION_CONFLICT");
        assertEquals(auditCount, fixture.count("audit"));
    }

    @Test
    void missingOrInvalidIdempotencyKeyRemains400ThroughActualWebAspect() throws Exception {
        rejected("initializePersonalWorkspace", null, null, alice, 400, "INVALID_ARGUMENT");
        rejected("initializePersonalWorkspace", null, " ", alice, 400, "INVALID_ARGUMENT");
        rejected("initializePersonalWorkspace", null, "x".repeat(257), alice, 400, "INVALID_ARGUMENT");
        assertEquals(0, fixture.count("tenant"));
    }

    @Test
    void invalidBodiesAndPaginationAreRejectedBeforeMutation() throws Exception {
        for (String body : List.of("{", "{\"name\":\"x\"}",
                "{\"tenantId\":\"tenant\",\"name\":\" \"}",
                "{\"tenantId\":\"tenant\",\"name\":\"x\",\"description\":\"" + "x".repeat(1001) + "\"}")) {
            rejected("createWorkspace", body, "create", alice, 400, "INVALID_ARGUMENT");
        }
        for (String body : List.of("{\"current\":0}", "{\"size\":101}", "{\"current\":9223372036854775807,\"size\":100}")) {
            var result = execute("listAccessibleWorkspaces", body, null, alice);
            assertEquals(400, result.getResponse().getStatus());
            var page = JSONUtil.parseObj(result.getResponse().getContentAsString());
            assertFalse(page.getBool("success"));
            assertEquals("PLATFORM_INVALID_ARGUMENT", page.getStr("code"));
            assertTrue(page.containsKey("records"));
        }
        rejected("renameWorkspace", "{\"scope\":{\"tenantId\":\"t\",\"workspaceId\":\"w\"},\"name\":\"x\"}",
                "rename", alice, 400, "INVALID_ARGUMENT");
        rejected("getWorkspace", "{\"scope\":{\"tenantId\":\" \",\"workspaceId\":\"w\"}}", null, alice, 400, "INVALID_ARGUMENT");
        rejected("updateWorkspacePreference", "{\"expectedPreferenceVersion\":0}", "preferences", alice, 400, "INVALID_ARGUMENT");
        rejected("updateWorkspacePreference", "{\"expectedPreferenceVersion\":0,\"favorites\":[null]}", "preferences", alice, 400, "INVALID_ARGUMENT");
        assertEquals(0, fixture.count("workspace"));
    }

    @Test
    void anonymousAndDisabledAccountsCannotInvokeCommands() throws Exception {
        rejected("initializePersonalWorkspace", null, "init", null, 401, "UNAUTHENTICATED");
        initialize();
        long count = fixture.count("command");
        fixture.jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        rejected("getWorkspacePreference", null, null, alice, 401, "UNAUTHENTICATED");
        rejected("initializePersonalWorkspace", null, "initialize", alice, 401, "UNAUTHENTICATED");
        assertEquals(count, fixture.count("command"));
    }

    @Test
    void otherUsersAndUnknownScopesAreEquallyInvisible() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 11:00:00"));
        var bob = login(2, "bob");
        rejected("getWorkspace", JSONUtil.createObj().set("scope", scope).toString(), null, bob, 404, "NOT_FOUND");
        rejected("getWorkspace", "{\"scope\":{\"tenantId\":\"missing\",\"workspaceId\":\"missing\"}}", null, bob, 404, "NOT_FOUND");
        var result = execute("listAccessibleWorkspaces", "{}", null, bob);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(0L, JSONUtil.parseObj(result.getResponse().getContentAsString()).getLong("total"));
        rejected("renameWorkspace", renameBody(scope, 0, "Not mine"), "rename", bob, 404, "NOT_FOUND");
    }

    @Test
    void unavailableSchedulerIs503WithSafeRetryMetadata() throws Exception {
        scheduler.dispose();
        var result = execute("initializePersonalWorkspace", null, "initialize", alice);
        assertEquals(503, result.getResponse().getStatus());
        var body = JSONUtil.parseObj(result.getResponse().getContentAsString());
        assertEquals("PLATFORM_AUTHORITY_UNAVAILABLE", body.getStr("code"));
        assertTrue(body.getJSONObject("data").getBool("retryableWithSameKey"));
        assertEquals(result.getResponse().getHeader("X-Trace-Id"), body.getJSONObject("data").getStr("traceId"));
        assertEquals(0, fixture.count("tenant"));
    }


    private JSONObject checkRequest(JSONObject scope, String type, String action) {
        return JSONUtil.createObj().set("scope", scope).set("resourceType", type).set("action", action);
    }

    @Test
    void singleAndBatchChecksPreserveOrderAndHideInternalAuthorizationFacts() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        var read = checkRequest(scope, "workspace", "workspace:read");
        var update = checkRequest(scope, "workspace", "workspace:update");
        var tenantCreate = checkRequest(JSONUtil.createObj().set("tenantId", scope.getStr("tenantId")), "tenant", "tenant:workspace:create");
        var unknown = checkRequest(scope, "workspace", "unknown:action");
        var single = ok("checkWorkspaceAccess", read.toString(), null).getJSONObject("data");
        assertTrue(single.getBool("allowed"));
        assertEquals("ALLOWED", single.getStr("reason"));
        assertFalse(single.containsKey("versions"));
        assertFalse(single.containsKey("principal"));
        var batch = ok("batchCheckWorkspaceAccess", JSONUtil.createObj().set("requests", List.of(read, update, tenantCreate, unknown, read)).toString(), null)
                .getJSONArray("data");
        assertEquals(5, batch.size());
        for (int index : List.of(0, 1, 2, 4)) assertTrue(batch.getJSONObject(index).getBool("allowed"));
        assertFalse(batch.getJSONObject(3).getBool("allowed"));
        assertEquals("UNKNOWN_ACTION", batch.getJSONObject(3).getStr("reason"));
        assertEquals("workspace:update", batch.getJSONObject(1).getStr("action"));
        assertEquals("tenant", batch.getJSONObject(2).getStr("resourceType"));
    }

    @Test
    void displayChecksHideOtherScopesAndDoNotGrantPermissionFromClientClaims() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 11:00:00"));
        var bob = login(2, "bob");
        var request = checkRequest(scope, "workspace", "workspace:read").set("allowed", true).set("role", "OWNER");
        var foreign = execute("checkWorkspaceAccess", request.toString(), null, bob);
        assertEquals(200, foreign.getResponse().getStatus());
        var decision = JSONUtil.parseObj(foreign.getResponse().getContentAsString()).getJSONObject("data");
        assertFalse(decision.getBool("allowed"));
        assertEquals("NOT_FOUND", decision.getStr("reason"));
        var missingScope = JSONUtil.createObj().set("tenantId", "missing").set("workspaceId", "missing");
        var missing = ok("checkWorkspaceAccess", checkRequest(missingScope, "workspace", "workspace:read").toString(), null).getJSONObject("data");
        assertEquals(decision.getStr("reason"), missing.getStr("reason"));
        rejected("checkWorkspaceAccess", request.toString(), null, null, 401, "UNAUTHENTICATED");
    }

    @Test
    void accessRequestLimitsAndScopeShapesAreValidated() throws Exception {
        var scope = JSONUtil.createObj().set("tenantId", "t").set("workspaceId", "w");
        rejected("checkWorkspaceAccess", checkRequest(scope, "article", "article:read").toString(), null, alice, 400, "INVALID_ARGUMENT");
        rejected("checkWorkspaceAccess", checkRequest(scope, "tenant", "tenant:read").toString(), null, alice, 400, "INVALID_ARGUMENT");
        rejected("checkWorkspaceAccess", checkRequest(JSONUtil.createObj().set("tenantId", "t"), "workspace", "workspace:read").toString(), null, alice, 400, "INVALID_ARGUMENT");
        rejected("batchCheckWorkspaceAccess", "{\"requests\":[]}", null, alice, 400, "INVALID_ARGUMENT");
        rejected("batchCheckWorkspaceAccess", "{\"requests\":[null]}", null, alice, 400, "INVALID_ARGUMENT");
        rejected("batchCheckWorkspaceAccess", "{\"requests\":[{\"scope\":{\"tenantId\":\"t\",\"workspaceId\":\"w\"}}]}", null, alice, 400, "INVALID_ARGUMENT");
        var check = checkRequest(scope, "workspace", "workspace:read");
        rejected("batchCheckWorkspaceAccess", JSONUtil.createObj().set("requests", java.util.Collections.nCopies(101, check)).toString(), null, alice, 400, "INVALID_ARGUMENT");
        assertEquals(0, fixture.count("workspace"));
    }

    @Test
    void authorizationAuthorityFailureIs503InsteadOfOrdinaryDenial() throws Exception {
        var scope = initialize().getJSONObject("workspace").getJSONObject("scope");
        fixture.jdbc.execute("DROP TABLE arte_platform_workspace_policy");
        var check = checkRequest(scope, "workspace", "workspace:read");
        rejected("checkWorkspaceAccess", check.toString(), null, alice, 503, "AUTHORITY_UNAVAILABLE");
        rejected("batchCheckWorkspaceAccess", JSONUtil.createObj().set("requests", List.of(check)).toString(), null, alice, 503, "AUTHORITY_UNAVAILABLE");
    }

    @Test
    void commandReceiptQueriesAreReadOnlyAndPreserveHistoricalResult() throws Exception {
        var initialized = ok("initializePersonalWorkspace", null, "init-query").getJSONObject("data");
        var scope = initialized.getJSONObject("value").getJSONObject("workspace").getJSONObject("scope");
        var renamed = ok("renameWorkspace", renameBody(scope, 0, "New name"), "rename-query").getJSONObject("data");
        long commands = fixture.count("command");
        long audits = fixture.count("audit");
        var queried = ok("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", initialized.getStr("operationId")).toString(), null)
                .getJSONObject("data");
        assertEquals(initialized.getStr("operationId"), queried.getStr("operationId"));
        assertEquals(initialized.getJSONObject("value"), queried.getJSONObject("value"));
        assertEquals(commands, fixture.count("command"));
        assertEquals(audits, fixture.count("audit"));
        var queriedRename = ok("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", renamed.getStr("operationId")).toString(), null)
                .getJSONObject("data");
        assertEquals("New name", queriedRename.getJSONObject("value").getStr("name"));
        assertFalse(queriedRename.containsKey("identity"));
        assertFalse(queriedRename.containsKey("requestDigest"));
        assertFalse(queriedRename.containsKey("operationKey"));
        rejected("getWorkspaceCommandReceipt", "{\"operationId\":\"missing\"}", null, alice, 404, "NOT_FOUND");
        rejected("getWorkspaceCommandReceipt", "{}", null, alice, 400, "INVALID_ARGUMENT");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 11:00:00"));
        rejected("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", renamed.getStr("operationId")).toString(), null, login(2, "bob"), 404, "NOT_FOUND");
    }

    @Test
    void revokedHistoricalPreferenceTargetBlocksReceiptAfterCurrentPreferencesAreCleared() throws Exception {
        var initial = initialize();
        var defaultScope = initial.getJSONObject("workspace").getJSONObject("scope");
        var created = ok("createWorkspace", JSONUtil.createObj().set("tenantId", defaultScope.getStr("tenantId")).set("name", "Second").toString(), "create-query")
                .getJSONObject("data");
        var scope = created.getJSONObject("value").getJSONObject("scope");
        var pref = ok("updateWorkspacePreference", JSONUtil.createObj().set("expectedPreferenceVersion", 0).set("startupScope", scope).set("favorites", List.of(scope)).toString(), "preference-query")
                .getJSONObject("data");
        ok("updateWorkspacePreference", "{\"expectedPreferenceVersion\":1,\"favorites\":[]}", "clear-query");
        fixture.jdbc.update("UPDATE arte_platform_workspace_member SET state=?,version=version+1 WHERE workspace_id=?",
                com.arte.core.enums.workspace.MembershipStateEnum.REVOKED.getValue(), scope.getStr("workspaceId"));
        rejected("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", pref.getStr("operationId")).toString(), null, alice, 404, "NOT_FOUND");
        rejected("getWorkspaceCommandReceipt", JSONUtil.createObj().set("operationId", created.getStr("operationId")).toString(), null, alice, 404, "NOT_FOUND");
    }

    @Test
    void controllerIsRegisteredOnlyWhenWorkspaceServicesAreEnabled() {
        new ApplicationContextRunner().withUserConfiguration(WorkspaceController.class, WorkspaceAccessController.class)
                .withPropertyValues("arte.platform.workspace.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("workspaceController"));
                    assertFalse(context.containsBean("workspaceAccessController"));
                });
        new ApplicationContextRunner().withUserConfiguration(WorkspaceController.class, WorkspaceAccessController.class)
                .withBean(ReactiveWorkspaceOperations.class, () -> operations)
                .withPropertyValues("arte.platform.workspace.enabled=true")
                .run(context -> {
                    assertNotNull(context.getBean(WorkspaceController.class));
                    assertNotNull(context.getBean(WorkspaceAccessController.class));
                });
    }
}
