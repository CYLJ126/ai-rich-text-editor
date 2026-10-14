package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.arte.app.platform.identity.*;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.web.advice.GlobalExceptionHandler;
import com.arte.app.web.advice.PlatformExceptionHandler;
import com.arte.app.web.aspect.WebAspect;
import com.arte.app.web.controller.platform.WorkspaceAccessController;
import com.arte.app.web.controller.platform.WorkspaceController;
import com.arte.core.pojo.UserOnlineInfo;
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
import java.time.LocalDateTime;
import java.util.List;

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
        operations = new ReactiveWorkspaceOperations(identities, new PlatformPersistenceExecutor(scheduler, fixture.tx),
                services.personal(), services.workspaces(), services.preferences(), services.scopes(), services.authorization(), services.writeGuard(), services.receiptQueries(), services.policies(),
                new com.arte.app.platform.workspace.WorkspaceDirectoryBrowser(services.workspaces(), cursors));
        var proxyFactory = new AspectJProxyFactory(new WorkspaceController(operations));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(new WebAspect());
        var accessProxy = new AspectJProxyFactory(new WorkspaceAccessController(operations));
        accessProxy.setProxyTargetClass(true);
        accessProxy.addAspect(new WebAspect());
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup((WorkspaceController) proxyFactory.getProxy(), (WorkspaceAccessController) accessProxy.getProxy())
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
