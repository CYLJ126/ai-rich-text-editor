package com.arte.app.platform.persistence;

import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.app.platform.ai.PlatformModelAclAccess;
import com.arte.app.platform.ai.PlatformModelAclMigrationService;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.service.platform.ModelAclScopeStoreServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.arte.app.platform.persistence.ModelResourceIntegrationTest.CLOCK;
import static com.arte.app.platform.persistence.ModelResourceIntegrationTest.V1;

final class ModelManagementFixture {

    final ModelResourceIntegrationTest f = new ModelResourceIntegrationTest();
    ModelAclScopeStoreServiceImpl scopes;
    PlatformModelAclAccess access;
    PlatformModelAclMigrationService migration;
    ModelResource model;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        String sql = Files.readString(Path.of("scripts", "arte-ai-model-acl-scope-ddl-mysql.sql"));
        if (f.f.mysql == null) {
            sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
        }
        try (java.sql.Connection connection = f.f.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        scopes = f.stores.aclScopes();
        access = new PlatformModelAclAccess(scopes, f.store, f.identity, f.f.tx, f.platform.authorization(), f.stores.tenants(), f.stores.workspaces(),
                f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences(), Schedulers.immediate(), CLOCK);
        migration = new PlatformModelAclMigrationService(scopes, f.identity, new RbacToolPermissionReader(f.f.source),
                f.f.tx, f.platform.authorization(), f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(),
                f.stores.workspaceMembers(), f.stores.preferences());
        model = f.create("model");
        f.models.associate(f.f.actor, f.association("v1", model, V1, ModelPublication.Source.FIXED, null));
        model = f.current(model);

    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

}
