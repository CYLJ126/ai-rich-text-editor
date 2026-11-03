package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        JSONObject created = http.ok(MODEL + "createModelResource", json(Map.of("scope", f.scope, "name", "HTTP database model", "modelKind", "PERSONAL")), "http-create", alice).getJSONObject("data").getJSONObject("value");
        String id = created.getStr("resourceId");
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
    void databaseHttpCannotAdoptAnExistingBareBindingOrActivateAManifest() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, false);
                http.rejected(MODEL + "associateModelResourceVersion", json(Map.of("scope", f.scope, "resourceId", model.resource().resourceId(),
                                "expectedVersion", 0, "binding", f.f.ref("binding", "text"), "source", "DATABASE", "catalogRevision", 1)),
                        "bare-adopt", alice, 409, "CAPABILITY_NOT_ENABLED");
                http.rejected(MODEL + "updateModelAclScope", json(Map.of("scope", f.scope, "expectedVersion", 0, "source", "DATABASE",
                        "bindings", List.of(f.f.ref("binding", "text")))), "bare-migrate", alice, 409, "CAPABILITY_NOT_ENABLED");
                assertEquals(0, f.count("arte_ai_model_version"));
                assertEquals(0, f.count("arte_ai_model_acl_scope"));
                assertEquals(0, f.count("arte_ai_invocation"));
            }
        });
    }

    @Test
    void databaseHttpCannotUseAFixedSourceLabelToBypassResourcePublication() {
        context().run(c -> {
            try (ManagementHttpHarness http = http(c)) {
                ModelResource model = prepare(c, http, false);
                http.rejected(MODEL + "associateModelResourceVersion", json(Map.of("scope", f.scope, "resourceId", model.resource().resourceId(),
                                "expectedVersion", 0, "binding", f.f.ref("binding", "text"), "source", "FIXED")),
                        "fixed-adopt", alice, 409, "CAPABILITY_NOT_ENABLED");
                assertEquals(0, f.count("arte_ai_model_version"));
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
