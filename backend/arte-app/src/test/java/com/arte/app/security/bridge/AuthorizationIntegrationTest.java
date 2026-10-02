package com.arte.app.security.bridge;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.AuthorizationRequest;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.model.security.PolicyOutcome;
import com.arte.core.pojo.UserContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizationIntegrationTest extends SecurityBridgeFixture {
    @Test
    void ownerPermissionsRemainAvailableWithNoThreadLocalOrSecurityContextInWorker() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.EDIT, CommonResourceAction.MANAGE_SHARING);
        UserContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        for (var action : new CommonResourceAction[]{CommonResourceAction.READ, CommonResourceAction.EDIT, CommonResourceAction.MANAGE_SHARING}) {
            assertEquals(PolicyOutcome.ALLOW, check(context, ARTICLE, action).policy().outcome());
        }
    }

    @Test
    void roleShareInheritedFromAncestorIsLiveAndDoesNotImplyEdit() {
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 2, TRUE, 1)", TENANT, WORKSPACE);
        jdbc.update("INSERT INTO arte_rbac_role VALUES (1, 'writers', '1')");
        jdbc.update("INSERT INTO arte_rbac_relation VALUES ('user_to_role', 'bob', 'writers')");
        jdbc.update("INSERT INTO arte_rt_share VALUES (1, 'CATALOG', 100, 'ROLE', NULL, 'writers', 'ACCESS', 'COMMENT', 'alice', CURRENT_TIMESTAMP)");
        login("bob", 2);
        var context = context(CommonResourceAction.READ, CommonResourceAction.COMMENT, CommonResourceAction.EDIT);
        check(context, ARTICLE, CommonResourceAction.READ);
        check(context, ARTICLE, CommonResourceAction.COMMENT);
        denied(() -> check(context, ARTICLE, CommonResourceAction.EDIT));
        jdbc.update("DELETE FROM arte_rbac_relation WHERE source = 'bob'");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
    }

    @Test
    void disabledRoleAndDeletedAncestorCannotAuthorize() {
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 2, TRUE, 1)", TENANT, WORKSPACE);
        jdbc.update("INSERT INTO arte_rbac_role VALUES (1, 'writers', '1')");
        jdbc.update("INSERT INTO arte_rbac_relation VALUES ('user_to_role', 'bob', 'writers')");
        jdbc.update("INSERT INTO arte_rt_share VALUES (1, 'CATALOG', 100, 'ROLE', NULL, 'writers', 'FULL_CONTROL', 'FULL_CONTROL', 'alice', CURRENT_TIMESTAMP)");
        login("bob", 2);
        var context = context(CommonResourceAction.EDIT);
        check(context, ARTICLE, CommonResourceAction.EDIT);
        jdbc.update("UPDATE arte_rbac_role SET status = '3'");
        denied(() -> check(context, ARTICLE, CommonResourceAction.EDIT));
        jdbc.update("UPDATE arte_rbac_role SET status = '1'");
        jdbc.update("UPDATE arte_rt_catalog SET is_delete = 1 WHERE id = 100");
        denied(() -> check(context, ARTICLE, CommonResourceAction.EDIT));
    }

    @Test
    void directShareAndCatalogPermissionUseCurrentExplicitSubject() {
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 2, TRUE, 1)", TENANT, WORKSPACE);
        jdbc.update("INSERT INTO arte_rt_share VALUES (1, 'ARTICLE', 10, 'USER', 'bob', NULL, 'READ_WRITE', NULL, 'alice', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO arte_rt_share VALUES (2, 'CATALOG', 100, 'USER', 'bob', NULL, 'FULL_CONTROL', NULL, 'alice', CURRENT_TIMESTAMP)");
        login("bob", 2);
        var context = context(CommonResourceAction.EDIT, CommonResourceAction.MANAGE_SHARING);
        check(context, ARTICLE, CommonResourceAction.EDIT);
        denied(() -> check(context, ARTICLE, CommonResourceAction.MANAGE_SHARING));
        check(context, ResourceRef.current("CATALOG", "100"), CommonResourceAction.MANAGE_SHARING);
        jdbc.update("DELETE FROM arte_rt_share");
        denied(() -> check(context, ARTICLE, CommonResourceAction.EDIT));
    }

    @Test
    void readAndOwnershipDoNotAutomaticallyAllowAiEgressOrExport() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS, CommonResourceAction.EXPORT);
        check(context, ARTICLE, CommonResourceAction.READ);
        for (var action : new CommonResourceAction[]{CommonResourceAction.AI_PROCESS, CommonResourceAction.EGRESS, CommonResourceAction.EXPORT}) {
            denied(() -> check(context, ARTICLE, action));
        }
        grant("10", 1, CommonResourceAction.AI_PROCESS);
        check(context, ARTICLE, CommonResourceAction.AI_PROCESS);
        denied(() -> check(context, ARTICLE, CommonResourceAction.EGRESS));
        jdbc.update("UPDATE arte_security_resource_grant SET enabled = FALSE, revision = 2");
        denied(() -> check(context, ARTICLE, CommonResourceAction.AI_PROCESS));
    }

    @Test
    void forgedContextIsNotARegisteredTask() {
        var original = context(CommonResourceAction.READ);
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 2, TRUE, 1)", TENANT, WORKSPACE);
        var forged = new ExecutionContext(new ExecutionScope(TENANT, WORKSPACE, new PrincipalRef("2", PrincipalType.USER)), original.traceId(),
                null, original.deadline(), null, original.authorizationScopes(), null, null, null);
        denied(() -> check(forged, ARTICLE, CommonResourceAction.READ));
        var expanded = new ExecutionContext(original.scope(), original.traceId(), null, original.deadline(), null,
                Set.of("resource.read", "resource.edit"), null, null, null);
        denied(() -> check(expanded, ARTICLE, CommonResourceAction.READ));
        var replay = ExecutionContext.create(original.scope(), original.traceId(), original.authorizationScopes());
        denied(() -> check(replay, ARTICLE, CommonResourceAction.READ));
    }

    @Test
    void currentMembershipAccountBindingTaskAndResourcePlacementOverridePreviousAllow() {
        var context = context(CommonResourceAction.READ);
        check(context, ARTICLE, CommonResourceAction.READ);
        jdbc.update("UPDATE arte_security_member SET enabled = FALSE");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
        jdbc.update("UPDATE arte_security_member SET enabled = TRUE");
        jdbc.update("UPDATE arte_rbac_user SET status = '3' WHERE id = 1");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
        jdbc.update("UPDATE arte_rbac_user SET status = '1' WHERE id = 1");
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled = FALSE");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled = TRUE");
        jdbc.update("UPDATE arte_security_task SET enabled = FALSE");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
        jdbc.update("UPDATE arte_security_task SET enabled = TRUE");
        jdbc.update("UPDATE arte_security_resource SET workspace_id = 'other' WHERE resource_id = '10'");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
    }

    @Test
    void delegationRequiresLiveServiceAndBothInitiatorAndExecutorTaskActions() {
        var context = context(CommonResourceAction.READ, CommonResourceAction.EDIT);
        var service = new PrincipalRef("worker-a", PrincipalType.SERVICE);
        var read = new AuthorizationRequest(context, service, ARTICLE, "resource.read");
        denied(() -> authorization.requireAuthorized(read, clock));
        jdbc.update("INSERT INTO arte_security_service VALUES ('worker-a', TRUE, 1)");
        denied(() -> authorization.requireAuthorized(read, clock));
        jdbc.update("INSERT INTO arte_security_task_action VALUES (?, 'SERVICE', 'worker-a', 'resource.read', TRUE, 1)", SecurityFingerprints.context(context));
        authorization.requireAuthorized(read, clock);
        denied(() -> authorization.requireAuthorized(new AuthorizationRequest(context, service, ARTICLE, "resource.edit"), clock));
        jdbc.update("UPDATE arte_security_task_action SET enabled = FALSE WHERE executor_type = 'USER' AND action_code = 'resource.read'");
        denied(() -> authorization.requireAuthorized(read, clock));
        jdbc.update("UPDATE arte_security_task_action SET enabled = TRUE WHERE executor_type = 'USER'");
        jdbc.update("UPDATE arte_security_service SET enabled = FALSE");
        denied(() -> authorization.requireAuthorized(read, clock));
    }

    @Test
    void missingDeletedUnknownResourcesAndDatabaseFailureNeverAllow() {
        var context = context(CommonResourceAction.READ);
        jdbc.update("UPDATE arte_rt_article SET is_delete = 1 WHERE id = 10");
        denied(() -> check(context, ARTICLE, CommonResourceAction.READ));
        assertEquals(PolicyOutcome.INDETERMINATE, authorization.evaluate(AuthorizationRequest.of(context, ResourceRef.current("ARTIFACT", "10"), CommonResourceAction.READ)).policy().outcome());
        jdbc.execute("DROP TABLE arte_security_member");
        var failure = assertThrows(BaseException.class, () -> check(context, ARTICLE, CommonResourceAction.READ));
        assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(), failure.error().code());
    }

    @Test
    void taskExpiryAndFiveSecondDecisionWindowAreExplicit() {
        var context = context(CommonResourceAction.READ);
        var decision = check(context, ARTICLE, CommonResourceAction.READ);
        assertEquals(NOW.plusSeconds(5), decision.policy().validUntil());
        clock.now = context.deadline();
        var failure = assertThrows(BaseException.class, () -> check(context, ARTICLE, CommonResourceAction.READ));
        assertEquals(CommonErrorCode.DEADLINE_EXCEEDED.code(), failure.error().code());
    }

    @Test
    void taskCannotReadAnotherOwnedResourceOrExpandVersionRangeAndActions() {
        var execution = context(CommonResourceAction.READ, CommonResourceAction.EDIT);
        jdbc.update("INSERT INTO arte_rt_article VALUES (12, 101, 'alice', FALSE, 0)");
        jdbc.update("INSERT INTO arte_security_resource VALUES ('ARTICLE', '12', ?, ?, TRUE, 1)", TENANT, WORKSPACE);
        denied(() -> check(execution, ResourceRef.saved("ARTICLE", "12", "v1"), CommonResourceAction.READ));
        denied(() -> check(execution, ARTICLE.withRange("new-range"), CommonResourceAction.READ));
        denied(() -> check(execution, ResourceRef.saved("ARTICLE", "10", "v2"), CommonResourceAction.READ));
        jdbc.update("UPDATE arte_security_task_resource_action SET enabled = FALSE WHERE action_code = 'resource.edit'");
        check(execution, ARTICLE, CommonResourceAction.READ);
        denied(() -> check(execution, ARTICLE, CommonResourceAction.EDIT));
    }

    @Test
    void legacyInheritanceCannotCrossUnverifiedTenantWorkspaceBoundary() {
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 2, TRUE, 1)", TENANT, WORKSPACE);
        jdbc.update("INSERT INTO arte_rt_share VALUES (1, 'CATALOG', 100, 'USER', 'bob', NULL, 'ACCESS', 'READ', 'alice', CURRENT_TIMESTAMP)");
        login("bob", 2);
        var execution = context(CommonResourceAction.READ);
        check(execution, ARTICLE, CommonResourceAction.READ);
        jdbc.update("UPDATE arte_security_resource SET tenant_id = 'other-tenant' WHERE resource_type = 'CATALOG'");
        var failure = assertThrows(BaseException.class, () -> check(execution, ARTICLE, CommonResourceAction.READ));
        assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(), failure.error().code());
    }

    private void denied(Runnable action) {
        var error = assertThrows(BaseException.class, action::run);
        assertEquals(CommonErrorCode.UNAUTHORIZED.code(), error.error().code());
    }
}
