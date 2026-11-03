package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.controller.platform.ModelResourceController;
import com.arte.app.web.controller.platform.ResourceAccessController;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.access.ResourceAccessViews;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.Authentication;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.*;

import static com.arte.app.platform.persistence.ModelResourceIntegrationTest.V1;
import static com.arte.app.platform.persistence.ModelResourceIntegrationTest.V2;
import static com.arte.core.constant.platform.PlatformActions.MODEL_SHARE;
import static com.arte.core.constant.platform.PlatformActions.RESOURCE_ACCESS_MANAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MVC／RBAC／Mapper／公共服务与模型服务；不调用模型网络、不改业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
class ModelManagementHttpIntegrationTest {

    final ModelAclExecutionIntegrationTest f = new ModelAclExecutionIntegrationTest();
    Scheduler scheduler;
    ManagementHttpHarness http;
    Authentication alice, bob;
    Principal bobPrincipal;
    static final String MODEL = "/ai-new/model-resource/", ACL = "/resource/access/";

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        scheduler = Schedulers.newBoundedElastic(3, 64, "management-http");
        PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(f.f.identity, new PlatformPersistenceExecutor(scheduler, f.f.f.tx));
        http = new ManagementHttpHarness(new ModelResourceController(executor, f.f.models, f.migration), new ResourceAccessController(executor, f.f.platform.resources(), f.f.platform.authorization()));
        alice = ManagementHttpHarness.login(f.f.f.jdbc, 1, "alice");
        f.f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        bobPrincipal = f.f.identity.resolveAuthenticatedName("bob").orElseThrow();
        bob = ManagementHttpHarness.login(f.f.f.jdbc, 2, "bob");
        f.f.f.tx.required(() -> {
            WorkspaceGovernanceLocks locks = new WorkspaceGovernanceLocks(f.f.f.tx, f.f.stores.tenants(), f.f.stores.workspaces(), f.f.stores.tenantMembers(), f.f.stores.workspaceMembers(), f.f.stores.preferences());
            locks.tenant(bobPrincipal.subjectId(), f.f.scope.tenantId());
            assertTrue(f.f.stores.tenantMembers().insert(new TenantMembership("bob-tenant", f.f.scope.tenantId(), bobPrincipal.subjectId(), TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(f.f.stores.workspaceMembers().insert(new WorkspaceMembership("bob-space", f.f.scope, bobPrincipal.subjectId(), WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        if (http != null) {
            http.close();
        }
        if (scheduler != null) {
            scheduler.dispose();
        }
        f.cleanup();
    }

    String json(Object value) {
        return f.f.f.json.write(value);
    }

    Map<String, Object> modelTarget() {
        return Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId());
    }

    Map<String, Object> target() {
        return Map.of("scope", f.f.scope, "resourceType", "model", "resourceId", f.model.resource().resourceId());
    }

    JSONObject model(String operation, Object body, String key) {
        return http.ok(MODEL + operation, json(body), key, alice).getJSONObject("data");
    }

    JSONObject acl(String operation, Object body, String key) {
        return http.ok(ACL + operation, json(body), key, alice).getJSONObject("data");
    }

    long version() {
        return acl("getResourceAccess", Map.of("target", target()), null).getLong("aclVersion");
    }

    Map<String, Object> grant(long version, String subject, List<String> actions) {
        return Map.of("target", target(), "expectedAclVersion", version, "recipient", Map.of("kind", "SUBJECT", "subjectId", subject), "actions", actions);
    }

    boolean check(Authentication auth, String action) {
        return http.ok(ACL + "checkResourceAccess", json(Map.of("target", target(), "action", action)), null, auth).getJSONObject("data").getBool("allowed");
    }

    JSONObject give(String key, List<String> actions) {
        return acl("grantResourceAccess", grant(version(), bobPrincipal.subjectId(), actions), key);
    }

    void revoke(JSONObject grant, String key) {
        acl("revokeResourceGrant", Map.of("target", target(), "grantId", grant.getStr("id"), "expectedAclVersion", version(), "expectedGrantVersion", grant.getLong("version")), key);
    }

    @Test
    void createQueryAssociateListAndDisablePreserveStableIdentityAndSafePublicationShape() {
        JSONObject draft = model("createModelResource", Map.of("scope", f.f.scope, "name", "HTTP draft", "modelKind", "PERSONAL"), "create").getJSONObject("value");
        assertEquals("DRAFT", draft.getStr("state"));
        assertEquals(f.f.f.actor.principal().subjectId(), draft.getStr("createdBy"));
        assertEquals(draft.getStr("resourceId"), model("createModelResource", Map.of("scope", f.f.scope, "name", "HTTP draft", "modelKind", "PERSONAL"), "create").getJSONObject("value").getStr("resourceId"));
        assertEquals(f.model.resource().resourceId(), model("getModelResource", modelTarget(), null).getStr("resourceId"));
        Map<String, Object> association = Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 1, "binding", V2, "source", "FIXED");
        JSONObject first = model("associateModelResourceVersion", association, "v2");
        JSONObject replay = model("associateModelResourceVersion", association, "v2");
        assertEquals(first.getStr("operationId"), replay.getStr("operationId"));
        assertEquals(2L, first.getLong("resultVersion"));
        assertEquals(f.model.resource().resourceId(), first.getJSONObject("value").getStr("resourceId"));
        String visible = first.toString();
        for (String hidden : List.of("\"credential\":", "endpoint", "example.invalid", "credential-independent", "snapshot_digest", "target")) {
            assertFalse(visible.contains(hidden), visible);
        }
        JSONObject versions = model("listModelResourceVersions", modelTarget(), null);
        assertEquals(2L, versions.getLong("total"));
        assertEquals(Set.of(V1.version(), V2.version()), versions.getJSONArray("records").stream().map(value -> ((JSONObject) value).getJSONObject("binding").getStr("version")).collect(java.util.stream.Collectors.toSet()));
        JSONObject disabled = model("disableModelResource", Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 2), "disable").getJSONObject("value");
        assertEquals("DISABLED", disabled.getStr("state"));
        assertEquals(3L, disabled.getLong("version"));
    }

    @Test
    void publicationRequiresBothModelPublishAndCurrentPlatformConfigurationPermission() {
        f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
        http.rejected(MODEL + "associateModelResourceVersion", json(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 1, "binding", V2, "source", "FIXED")), "denied", alice, 403, "ACCESS_DENIED");
        assertEquals(1, f.f.models.versions(f.f.f.actor, f.f.scope, f.model.resource(), new PageQuery(1, 100)).total());
    }

    @Test
    void publicReadShareAndUseAreDistinctAndCannotEscalateToManagement() {
        assertFalse(check(bob, "model:use"));
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, bob, 403, "ACCESS_DENIED");
        give("use", List.of("model:use"));
        assertTrue(check(bob, "model:use"));
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, bob, 403, "ACCESS_DENIED");
        give("read", List.of("model:read"));
        assertEquals(f.model.resource().resourceId(), http.ok(MODEL + "getModelResource", json(modelTarget()), null, bob).getJSONObject("data").getStr("resourceId"));
        http.rejected(ACL + "getResourceAccess", json(Map.of("target", target())), null, bob, 403, "ACCESS_DENIED");
        http.rejected(ACL + "grantResourceAccess", json(grant(version(), bobPrincipal.subjectId(), List.of("resource:access:manage"))), "escalate", bob, 403, "ACCESS_DENIED");
        http.rejected(MODEL + "disableModelResource", json(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 1)), "disable", bob, 403, "ACCESS_DENIED");
    }

    @Test
    void grantingAndRevokingAreIdempotentAndPreserveIndependentSources() {
        Map<String, Object> command = grant(version(), bobPrincipal.subjectId(), List.of("model:use", "model:read"));
        JSONObject first = acl("grantResourceAccess", command, "grant");
        Map<String, Object> reordered = new HashMap<>(command);
        reordered.put("actions", List.of("model:read", "model:use"));
        JSONObject replay = acl("grantResourceAccess", reordered, "grant");
        assertEquals(first.getStr("operationId"), replay.getStr("operationId"));
        JSONObject other = give("independent", List.of("model:use")).getJSONObject("value").getJSONArray("grants").getJSONObject(0);
        JSONObject firstUse = first.getJSONObject("value").getJSONArray("grants").stream().map(value -> (JSONObject) value).filter(value -> value.getStr("action").equals("model:use")).findFirst().orElseThrow();
        revoke(firstUse, "revoke-first");
        assertTrue(check(bob, "model:use"));
        Map<String, Object> revoke = Map.of("target", target(), "grantId", other.getStr("id"), "expectedAclVersion", version(), "expectedGrantVersion", 0);
        JSONObject revoked = acl("revokeResourceGrant", revoke, "revoke");
        assertEquals(revoked.getStr("operationId"), acl("revokeResourceGrant", revoke, "revoke").getStr("operationId"));
        assertFalse(check(bob, "model:use"));
        assertTrue(check(bob, "model:read"));
    }

    @Test
    void sharedModeClosesUseWithoutRemovingAnIndependentReadGrant() {
        give("read", List.of("model:read"));
        acl("changeResourceVisibility", Map.of("target", target(), "expectedAclVersion", version(), "visibility", "SPACE_SHARED", "sharedGrants", List.of(Map.of("recipient", Map.of("kind", "SPACE"), "actions", List.of("model:use")))), "shared");
        assertTrue(check(bob, "model:use"));
        JSONObject visible = acl("getResourceAccess", Map.of("target", target()), null);
        assertEquals("SPACE_SHARED", visible.getStr("visibility"));
        acl("changeResourceVisibility", Map.of("target", target(), "expectedAclVersion", version(), "visibility", "EXPLICIT", "sharedGrants", List.of()), "private");
        assertFalse(check(bob, "model:use"));
        assertTrue(check(bob, "model:read"));
    }

    @Test
    void exactRoleSharingDoesNotIncludeOtherRolesAndRejoiningDoesNotRestoreDirectGrant() {
        JSONObject direct = give("direct", List.of("model:use")).getJSONObject("value").getJSONArray("grants").getJSONObject(0);
        f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET generation=generation+1,version=version+1 WHERE subject_id=?", bobPrincipal.subjectId());
        assertFalse(check(bob, "model:use"));
        acl("changeResourceVisibility", Map.of("target", target(), "expectedAclVersion", version(), "visibility", "SPACE_SHARED", "sharedGrants", List.of(Map.of("recipient", Map.of("kind", "SPACE_ROLE", "role", "MEMBER"), "actions", List.of("model:use")))), "role");
        assertTrue(check(bob, "model:use"));
        f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=1,version=version+1 WHERE subject_id=?", bobPrincipal.subjectId());
        assertFalse(check(bob, "model:use"));
        assertNotNull(direct.getStr("id"));
    }

    @Test
    void wrongSpaceWrongTenantAndUnknownResourcesCannotReadOrManageModels() {
        Scope second = f.f.platform.workspaces().create(f.f.f.actor, new WorkspaceCommands.CreateWorkspace(f.f.meta("second"), f.f.scope.tenantId(), "Second", "")).value().scope();
        for (Scope wrong : List.of(second, new Scope("another-tenant", "another-space"))) {
            http.rejected(MODEL + "getModelResource", json(Map.of("scope", wrong, "resourceId", f.model.resource().resourceId())), null, alice, 404, "NOT_FOUND");
            http.rejected(ACL + "getResourceAccess", json(Map.of("target", Map.of("scope", wrong, "resourceType", "model", "resourceId", f.model.resource().resourceId()))), null, alice, 404, "NOT_FOUND");
        }
        http.rejected(MODEL + "getModelResource", json(Map.of("scope", f.f.scope, "resourceId", "unknown")), null, alice, 404, "NOT_FOUND");
    }

    @Test
    void versionsAndAclListsAreBoundedAndContainNoInternalProofOrMembershipGeneration() {
        JSONObject page = acl("listResourceGrants", Map.of("target", target(), "current", 1, "size", 2), null);
        assertEquals(8L, page.getLong("total"));
        assertEquals(2, page.getJSONArray("records").size());
        String visible = page.toString();
        for (String hidden : List.of("membershipGeneration", "issuedBy", "sourceOperationId", "AuthorizationVersions", "principal")) {
            assertFalse(visible.contains(hidden), visible);
        }
        http.rejected(ACL + "listResourceGrants", json(Map.of("target", target(), "size", 101)), null, alice, 400, "INVALID_ARGUMENT");
        http.rejected(MODEL + "listModelResourceVersions", json(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "current", 0)), null, alice, 400, "INVALID_ARGUMENT");
    }

    @Test
    void missingAndMalformedVersionSourceBindingAndIdempotencyInputsAreRejected() {
        Map<String, Object> create = Map.of("scope", f.f.scope, "name", "Draft", "modelKind", "PERSONAL");
        http.rejected(MODEL + "createModelResource", json(create), null, alice, 400, "INVALID_ARGUMENT");
        http.rejected(MODEL + "createModelResource", json(create), " bad ", alice, 400, "INVALID_ARGUMENT");
        http.rejected(ACL + "grantResourceAccess", json(Map.of("target", target(), "recipient", Map.of("kind", "SUBJECT", "subjectId", bobPrincipal.subjectId()), "actions", List.of("model:use"))), "key", alice, 400, "INVALID_ARGUMENT");
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("source", "DATABASE"), Map.of("source", "FIXED", "catalogRevision", 1), Map.of("source", "AUTO"), Map.of("source", "FIXED", "expectedVersion", -1))) {
            Map<String, Object> value = new HashMap<>(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 1, "binding", V2));
            value.putAll(invalid);
            http.rejected(MODEL + "associateModelResourceVersion", json(value), "bad-association", alice, 400, "INVALID_ARGUMENT");
        }
        assertEquals(1, f.f.models.versions(f.f.f.actor, f.f.scope, f.model.resource(), new PageQuery(1, 100)).total());
    }

    @Test
    void malformedRecipientsDuplicateActionsAndForgedAuthorityFieldsAreRejected() {
        Map<String, Object> base = grant(version(), bobPrincipal.subjectId(), List.of("model:use"));
        for (Map<String, Object> recipient : List.<Map<String, Object>>of(Map.of("kind", "SPACE", "subjectId", bobPrincipal.subjectId()), Map.of("kind", "SPACE_ROLE"), Map.of("kind", "SUBJECT", "subjectId", bobPrincipal.subjectId(), "generation", 0), Map.of("kind", "SPACE", "scope", f.f.scope))) {
            Map<String, Object> input = new HashMap<>(base);
            input.put("recipient", recipient);
            http.rejected(ACL + "grantResourceAccess", json(input), "invalid", alice, 400, "INVALID_ARGUMENT");
        }
        Map<String, Object> duplicate = new HashMap<>(base);
        duplicate.put("actions", List.of("model:use", "model:use"));
        http.rejected(ACL + "grantResourceAccess", json(duplicate), "duplicate", alice, 400, "INVALID_ARGUMENT");
        for (String field : List.of("actor", "owner", "createdBy", "publication", "traceId")) {
            Map<String, Object> input = new HashMap<>(Map.of("scope", f.f.scope, "name", "forged", "modelKind", "PERSONAL"));
            input.put(field, Map.of("subjectId", bobPrincipal.subjectId()));
            http.rejected(MODEL + "createModelResource", json(input), "forged", alice, 400, "INVALID_ARGUMENT");
        }
    }

    @Test
    void unauthenticatedDisabledAndRecreatedAccountsCannotUseOldSessions() {
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, null, 401, "UNAUTHENTICATED");
        http.rejected(ACL + "getResourceAccess", json(Map.of("target", target())), null, null, 401, "UNAUTHENTICATED");
        f.f.f.jdbc.update("UPDATE arte_rbac_user SET status=0 WHERE id=1");
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, alice, 401, "UNAUTHENTICATED");
        f.f.f.jdbc.update("UPDATE arte_rbac_user SET status=1,create_time=? WHERE id=1", java.sql.Timestamp.valueOf("2026-10-31 13:00:00"));
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, alice, 401, "UNAUTHENTICATED");
    }

    @Test
    void staleVersionsAndChangedIdempotentBodiesDoNotWrite() {
        long acl = version();
        give("winner", List.of("model:read"));
        http.rejected(ACL + "grantResourceAccess", json(grant(acl, bobPrincipal.subjectId(), List.of("model:use"))), "stale", alice, 409, "VERSION_CONFLICT");
        http.rejected(ACL + "grantResourceAccess", json(grant(acl, bobPrincipal.subjectId(), List.of("model:use"))), "winner", alice, 409, "IDEMPOTENCY_CONFLICT");
        http.rejected(MODEL + "disableModelResource", json(Map.of("scope", f.f.scope, "resourceId", f.model.resource().resourceId(), "expectedVersion", 0)), "stale-disable", alice, 409, "VERSION_CONFLICT");
        assertTrue(check(alice, "model:use"));
        assertFalse(check(bob, "model:use"));
    }

    @Test
    void replayRechecksCurrentManagementAndCannotRevealOldGrantResultsAfterRevocation() {
        Map<String, Object> input = grant(version(), bobPrincipal.subjectId(), List.of("model:read"));
        acl("grantResourceAccess", input, "grant");
        List<ResourceAccessViews.GrantView> grants = f.f.platform.resources().listGrants(f.f.f.actor, f.f.scope, f.model.resource(), new PageQuery(1, 100)).records();
        for (ResourceAccessViews.GrantView value : grants) {
            if (Set.of(RESOURCE_ACCESS_MANAGE, MODEL_SHARE).contains(value.action())) {
                long current = f.f.stores.grants().findAccess(f.model.resource()).orElseThrow().aclVersion();
                f.f.platform.resources().revoke(f.f.f.actor, new ResourceAccessCommands.RevokeGrant(f.f.meta("drop-" + value.action().value()), f.f.scope, f.model.resource(), value.id(), current, value.version()));
            }
        }
        http.rejected(ACL + "grantResourceAccess", json(input), "grant", alice, 403, "ACCESS_DENIED");
    }

    @Test
    void batchChecksRetainOrderAndNeverExposeAuthorizationVersions() {
        List<Map<String, Object>> checks = List.of(Map.of("target", target(), "action", "model:read"), Map.of("target", target(), "action", "model:use"), Map.of("target", target(), "action", "model:read"));
        String output = http.ok(ACL + "batchCheckResourceAccess", json(Map.of("requests", checks)), null, alice).toString();
        cn.hutool.json.JSONArray data = cn.hutool.json.JSONUtil.parseObj(output).getJSONArray("data");
        assertEquals(3, data.size());
        assertEquals("model:read", data.getJSONObject(0).getStr("action"));
        assertEquals("model:use", data.getJSONObject(1).getStr("action"));
        assertFalse(output.contains("aclVersion"));
        assertFalse(output.contains("workspaceEpoch"));
        http.rejected(ACL + "batchCheckResourceAccess", json(Map.of("requests", Collections.nCopies(101, checks.getFirst()))), null, alice, 400, "INVALID_ARGUMENT");
    }

    @Test
    void migrationRequiresExplicitCommandAndManagerAndEmptyManifestDoesNotRevertToLegacy() {
        assertFalse(model("getModelAclScope", Map.of("scope", f.f.scope), null).getBool("migrated"));
        assertEquals(0, f.f.count("arte_ai_model_acl_scope"));
        Map<String, Object> activate = Map.of("scope", f.f.scope, "source", "FIXED", "expectedVersion", 0, "bindings", List.of(V1));
        http.rejected(MODEL + "updateModelAclScope", json(activate), "activate", bob, 403, "ACCESS_DENIED");
        JSONObject receipt = model("updateModelAclScope", activate, "activate");
        assertTrue(receipt.getJSONObject("value").getBool("migrated"));
        assertEquals(receipt.getStr("operationId"), model("updateModelAclScope", activate, "activate").getStr("operationId"));
        model("updateModelAclScope", Map.of("scope", f.f.scope, "source", "FIXED", "expectedVersion", 1, "bindings", List.of()), "empty");
        JSONObject state = model("getModelAclScope", Map.of("scope", f.f.scope), null);
        assertTrue(state.getBool("migrated"));
        assertEquals(2L, state.getLong("version"));
        assertTrue(f.discover().isEmpty());
    }

    @Test
    void migrationRejectsMissingMappingsDuplicateBindingsAndSourceSwitches() {
        for (List<com.arte.core.pojo.reference.DefinitionRef> refs : List.of(List.of(V2), List.of(V1, V1))) {
            http.rejected(MODEL + "updateModelAclScope", json(Map.of("scope", f.f.scope, "source", "FIXED", "expectedVersion", 0, "bindings", refs)), "invalid", alice,
                    refs.size() == 1 ? 404 : 400, refs.size() == 1 ? "NOT_FOUND" : "INVALID_ARGUMENT");
        }
        http.rejected(MODEL + "updateModelAclScope", json(Map.of("scope", f.f.scope, "source", "DATABASE", "expectedVersion", 0, "bindings", List.of(V1))), "switch", alice, 409, "CAPABILITY_NOT_ENABLED");
        assertEquals(0, f.f.count("arte_ai_model_acl_scope"));
    }

    @Test
    void concurrentAclCommandsWithOneExpectedVersionHaveOneWinner() throws Exception {
        Map<String, Object> input = grant(version(), bobPrincipal.subjectId(), List.of("model:use"));
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "race-" + i;
                results.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return http.execute(ACL + "grantResourceAccess", json(input), key, alice).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(15, TimeUnit.SECONDS));
            }
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(1, version());
        assertTrue(check(bob, "model:use"));
    }

    @Test
    void schedulerOverloadUsesSanitized503Responses() {
        scheduler.dispose();
        http.rejected(ACL + "getResourceAccess", json(Map.of("target", target())), null, alice, 503, "AUTHORITY_UNAVAILABLE");
    }

    @Test
    void storageFaultFailsTheWholeBatchAndNeverLeaksSqlOrPartialAllows() {
        f.f.f.jdbc.execute("DROP TABLE arte_platform_resource_grant");
        String request = json(Map.of("target", target(), "action", "model:use"));
        http.rejected(ACL + "checkResourceAccess", request, null, alice, 503, "AUTHORITY_UNAVAILABLE");
        org.springframework.test.web.servlet.MvcResult response = http.execute(ACL + "batchCheckResourceAccess",
                json(Map.of("requests", List.of(Map.of("target", target(), "action", "model:read"), Map.of("target", target(), "action", "model:use")))), null, alice);
        assertEquals(503, response.getResponse().getStatus());
        String contents = ManagementHttpHarness.contents(response);
        assertFalse(contents.contains("arte_platform_resource_grant"));
        assertFalse(contents.contains("SELECT"));
        assertFalse(contents.contains("allowed"));
        assertTrue(cn.hutool.json.JSONUtil.parseObj(contents).getJSONObject("data").getBool("retryableWithSameKey"));
    }

    @Test
    void rbacAdminAuthorityDoesNotBypassPrivateResourceAcl() {
        Authentication admin = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(bob.getPrincipal(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("admin")));
        http.rejected(MODEL + "getModelResource", json(modelTarget()), null, admin, 403, "ACCESS_DENIED");
        assertFalse(check(admin, "model:use"));
    }

    @Test
    void coreAclHttpRemainsAvailableWhenAiIsDisabled() {
        new ApplicationContextRunner().withUserConfiguration(ResourceAccessController.class, ModelResourceController.class)
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true", "arte.ai-new.enabled=false")
                .withBean(PlatformAuthenticatedExecutor.class, () -> org.mockito.Mockito.mock(PlatformAuthenticatedExecutor.class))
                .withBean(com.arte.core.api.access.ResourceAccessService.class, () -> f.f.platform.resources())
                .withBean("platformAuthorizationService", com.arte.core.api.access.AuthorizationService.class, () -> f.f.platform.authorization())
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    assertEquals(1, c.getBeansOfType(ResourceAccessController.class).size());
                    assertTrue(c.getBeansOfType(ModelResourceController.class).isEmpty());
                });
    }

    @Test
    void newControllersAreAbsentWithoutFeatureFlags() {
        new ApplicationContextRunner().withUserConfiguration(ResourceAccessController.class, ModelResourceController.class).run(c -> {
            assertNull(c.getStartupFailure());
            assertTrue(c.getBeansOfType(ResourceAccessController.class).isEmpty());
            assertTrue(c.getBeansOfType(ModelResourceController.class).isEmpty());
        });
    }
}
