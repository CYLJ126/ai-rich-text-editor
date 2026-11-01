package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.web.controller.platform.ModelResourceController;
import com.arte.app.web.controller.platform.ResourceAccessController;
import com.arte.core.pojo.access.ResourceAccessViews;
import com.arte.core.pojo.platform.PageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产装配＋方法安全＋管理 HTTP＋真实受理／派发原子写；网关禁止真实模型网络。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
class ModelAclHttpRuntimeIntegrationTest {

    final ModelAclRuntimeIntegrationTest f = new ModelAclRuntimeIntegrationTest();
    static final String MODEL = "/ai-new/model-resource/", ACL = "/resource/access/";
    static final Duration WAIT = Duration.ofSeconds(15);
    Authentication alice;

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class MethodSecurity {
    }

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        alice = ManagementHttpHarness.login(f.f.f.jdbc, 1, "alice");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ApplicationContextRunner context() {
        return f.context().withUserConfiguration(ModelResourceController.class, ResourceAccessController.class, MethodSecurity.class);
    }

    String json(Object value) {
        return f.f.f.json.write(value);
    }

    ManagementHttpHarness http(ApplicationContext c) {
        return new ManagementHttpHarness(c.getBean(ModelResourceController.class), c.getBean(ResourceAccessController.class));
    }

    ModelResource prepare(ApplicationContext c, ManagementHttpHarness http, boolean activate) {
        DatabaseAiControlService db = c.getBean(DatabaseAiControlService.class);
        DatabaseAiTestCatalog.publish(db, c, f.f.f.actor, f.f.meta("import"));
        db.ensure(f.f.f.actor, f.scope);
        JSONObject created = http.ok(MODEL + "createModelResource", json(Map.of("scope", f.scope, "name", "HTTP database model")), "http-create", alice).getJSONObject("data").getJSONObject("value");
        String id = created.getStr("resourceId");
        JSONObject associated = http.ok(MODEL + "associateModelResourceVersion", json(Map.of("scope", f.scope, "resourceId", id, "expectedVersion", 0,
                "binding", f.f.ref("binding", "text"), "source", "DATABASE", "catalogRevision", 1)), "http-associate", alice).getJSONObject("data");
        assertEquals(id, associated.getJSONObject("value").getStr("resourceId"));
        if (activate) {
            activate(http);
        }
        return c.getBean(ModelResourceService.class).get(f.f.f.actor, f.scope, new com.arte.core.pojo.access.ResourceRef(f.scope.tenantId(), com.arte.core.constant.platform.PlatformResourceTypes.MODEL, id));
    }

    void activate(ManagementHttpHarness http) {
        http.ok(MODEL + "updateModelAclScope", json(Map.of("scope", f.scope, "expectedVersion", 0, "source", "DATABASE", "bindings", List.of(f.f.ref("binding", "text")))), "http-activate", alice);
    }

    Map<String, Object> target(ModelResource model) {
        return Map.of("scope", f.scope, "resourceType", "model", "resourceId", model.resource().resourceId());
    }

    void revoke(ApplicationContext c, ManagementHttpHarness http, ModelResource model) {
        ResourceAccessViews.GrantView grant = c.getBean(com.arte.core.api.access.ResourceAccessService.class).listGrants(f.f.f.actor, f.scope, model.resource(), new PageQuery(1, 100)).records().stream()
                .filter(value -> value.action().equals(MODEL_USE)).findFirst().orElseThrow();
        long version = http.ok(ACL + "getResourceAccess", json(Map.of("target", target(model))), null, alice).getJSONObject("data").getLong("aclVersion");
        http.ok(ACL + "revokeResourceGrant", json(Map.of("target", target(model), "grantId", grant.id(), "expectedAclVersion", version, "expectedGrantVersion", grant.version())), "http-revoke", alice);
    }

    AiEntitlement resolve(ApplicationContext c, ExecutionContext execution) {
        return c.getBean(AiResourceEntitlementService.class).resolve(f.f.ref("binding", "text"), f.f.ref("capability", "text"), execution).block(WAIT);
    }

    @Test
    void httpActivationAndRevocationReachProductionEntitlementWithoutChangingBudgetOrOwner() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, true);
                ExecutionContext execution = f.execution(c, "discovery");
                AiEntitlement allowed = resolve(c, execution);
                assertEquals(model.resource(), allowed.modelAccess().resource());
                assertEquals(f.f.f.actor.principal().subjectId(), allowed.executionOwner().subjectId());
                assertEquals(f.scope.workspaceId(), allowed.executionOwner().workspaceId());
                long accounts = f.count("arte_ai_account");
                Invocation accepted = f.accept(c);
                assertEquals(Invocation.State.ACCEPTED, accepted.state());
                revoke(c, http, model);
                assertThrows(RuntimeException.class, () -> resolve(c, execution));
                assertEquals(1, c.getBean(BudgetDefinitionProvider.class).available(execution, BudgetDefinitionProvider.Operation.EXECUTE).block(WAIT).size());
                assertEquals(accounts, f.count("arte_ai_account"));
            }
        });
    }

    @Test
    void unMigratedHttpAclChangesDoNotSilentlySwitchLegacyAndActivationRemovesGrantFallback() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, false);
                ExecutionContext execution = f.execution(c, "legacy");
                assertNull(resolve(c, execution).modelAccess());
                revoke(c, http, model);
                assertNull(resolve(c, execution).modelAccess());
                assertEquals(0, f.count("arte_ai_model_acl_scope"));
                activate(http);
                assertThrows(RuntimeException.class, () -> resolve(c, execution));
                assertEquals(1, f.count("arte_ai_model_acl_scope"));
            }
        });
    }

    @Test
    void httpRevokeAfterAdmissionPreflightRollsBackAllExecutionWrites() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, true);
                AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
                AiAdmissionAuthorizationGuard racing = check -> guard.authorize(check).doOnNext(ignored -> revoke(c, http, model));
                MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
                NewAiProperties properties = c.getBean(NewAiProperties.class);
                AdmissionAuthorization authorization = c.getBean(AdmissionAuthorization.class);
                AdmissionInvocationCoordinator coordinator = new AdmissionInvocationCoordinator(authorization, c.getBean(CapabilityCatalog.class), c.getBean(BindingManager.class), executions,
                        c.getBean(MybatisPayloadPersistence.class), executions, properties, Clock.systemUTC(), c.getBean(AiExecutionPolicyProvider.class), racing, c.getBean(PublishedAiCatalog.class), c.getBean(BudgetDefinitionProvider.class));
                ChatService chat = new DefaultChatService(authorization, c.getBean(ConversationService.class), c.getBean(BindingManager.class), c.getBean(ContextService.class), coordinator,
                        Clock.systemUTC(), executions, executions, properties);
                String conversation = f.conversation(c);
                ExecutionContext execution = f.execution(c, "race-submit");
                assertThrows(RuntimeException.class, () -> chat.submit(f.request(conversation, execution), execution).block(WAIT));
                assertEquals(0, f.count("arte_ai_invocation"));
                assertEquals(0, f.count("arte_ai_turn"));
                assertEquals(0, f.count("arte_ai_outbox"));
                assertThrows(RuntimeException.class, () -> resolve(c, execution));
            }
        });
    }

    @Test
    void httpRevokeAfterDispatchPreflightLeavesNoAttemptOrBudgetReservation() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, true);
                Invocation invocation = f.accept(c);
                AiAdmissionAuthorizationGuard.Permit permit = c.getBean(AiAdmissionAuthorizationGuard.class).authorize(f.check(c, invocation, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block(WAIT);
                MybatisExecutionPersistence executions = c.getBean(MybatisExecutionPersistence.class);
                OutboxMessage message = executions.claim(OutboxMessage.Kind.DISPATCH, "http-worker", Duration.ofMinutes(1), 1).block(WAIT).getFirst();
                revoke(c, http, model);
                DispatchPreparationStore.Command command = new DispatchPreparationStore.Command(new ExecutionCommands.Version(message.owner(), message.invocationId(), invocation.version()), message,
                        "attempt", Duration.ofSeconds(30), f.f.money("1"), f.f.ref("rate", "text-price"), Duration.ofMinutes(5));
                assertThrows(RuntimeException.class, () -> executions.prepareDispatch(command, () -> permit.requireInCurrentTransaction(invocation.request())).block(WAIT));
                assertEquals(0, f.count("arte_ai_attempt"));
                assertEquals(0, f.count("arte_ai_reservation"));
                assertEquals(Invocation.State.ACCEPTED, executions.find(message.owner(), message.invocationId()).block(WAIT).state());
            }
        });
    }

    @Test
    void externalSendRechecksRightsChangedThroughManagementHttp() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, true);
                Invocation invocation = f.accept(c);
                AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
                AiAdmissionAuthorizationGuard.Check check = f.check(c, invocation, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND);
                assertNotNull(guard.authorize(check).block(WAIT).connection());
                revoke(c, http, model);
                assertThrows(RuntimeException.class, () -> guard.authorize(check).block(WAIT));
                assertEquals(0, f.count("arte_ai_attempt"));
            }
        });
    }

    @Test
    void methodSecurityAndTrustedSessionRejectAnonymousOrNameOnlyPrincipals() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                http.rejected(MODEL + "getModelAclScope", json(Map.of("scope", f.scope)), null, null, 401, "UNAUTHENTICATED");
                Authentication nameOnly = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
                http.rejected(MODEL + "getModelAclScope", json(Map.of("scope", f.scope)), null, nameOnly, 401, "UNAUTHENTICATED");
            }
        });
    }
}
