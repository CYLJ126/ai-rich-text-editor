package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.app.platform.ai.PlatformModelDraftConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production Spring wiring and property binding, with the existing isolated runtime fixture.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
class ModelDraftAssemblyIntegrationTest {

    final ModelAclRuntimeIntegrationTest f = new ModelAclRuntimeIntegrationTest();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        String ddl = Files.readString(Path.of("scripts/arte-ai-model-draft-ddl-mysql.sql"));
        if (f.f.f.mysql == null) ddl = ddl.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                .replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
        try (var connection = f.f.f.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    @Test
    void preparationIsDisabledByDefaultAndDoesNotAlterExistingRuntime() {
        f.context().withUserConfiguration(PlatformModelDraftConfiguration.class).run(c -> {
            assertNull(c.getStartupFailure());
            assertFalse(c.containsBean("modelDraftService"));
            assertNotNull(c.getBean(ModelResourceService.class));
        });
    }

    @Test
    void preparationWiresRealStoresAndListsPrivateDraftWithoutAiTemplateOrPlatformPermission() {
        f.context().withUserConfiguration(PlatformModelDraftConfiguration.class).withPropertyValues(
                        "arte.ai-new-model-management.enabled=true", "arte.ai-new-model-management.active-key-id=primary",
                        "arte.ai-new-model-management.fingerprint-key-id=primary", "arte.ai-new-model-management.encryption-keys.primary=" + Base64.getEncoder().encodeToString(new byte[16]))
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
                    var model = c.getBean(ModelResourceService.class).create(f.f.f.actor, new ModelResourceCommands.Create(f.f.meta("create-preparation"), f.scope, "Preparation")).value();
                    var drafts = c.getBean(ModelDraftService.class);
                    assertEquals(model.resource(), drafts.list(f.f.f.actor, f.scope, null, 20).records().getFirst().resource());
                    assertNull(drafts.get(f.f.f.actor, f.scope, model.resource()).draft());
                });
    }

    @Test
    void explicitlyEnabledPreparationRejectsMissingEncryptionKeysAtStartup() {
        f.context().withUserConfiguration(PlatformModelDraftConfiguration.class).withPropertyValues("arte.ai-new-model-management.enabled=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }
}
