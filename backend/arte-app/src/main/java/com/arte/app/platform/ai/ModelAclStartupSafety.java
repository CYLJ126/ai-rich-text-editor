package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.ConfigurationAiResourceEntitlementService;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.model.ModelAclAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 已存在迁移清单时，禁用公共服务、自定义旧资格服务或换数据源不能静默回到 Grant。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class ModelAclStartupSafety implements SmartInitializingSingleton {

    private final DataSource source;
    private final NewAiProperties properties;
    private final ObjectProvider<ModelAclAccess> access;
    private final ObjectProvider<AiResourceEntitlementService> entitlements;

    public ModelAclStartupSafety(@Qualifier("appDataSource") DataSource source, NewAiProperties properties,
                                 ObjectProvider<ModelAclAccess> access, ObjectProvider<AiResourceEntitlementService> entitlements) {
        this.source = source;
        this.properties = properties;
        this.access = access;
        this.entitlements = entitlements;
    }

    static void requireReady(NewAiProperties properties, ModelAclAccess gate, AiResourceEntitlementService service) {
        if (gate == null || !(service instanceof ConfigurationAiResourceEntitlementService configured) || !configured.usesModelAcl(gate)
                || !"appDataSource".equals(properties.dataSourceBean())) {
            throw new IllegalStateException("Migrated model scopes require the public model ACL gate and shared appDataSource");
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (Connection connection = source.getConnection()) {
            boolean present = false;
            // JDBC 元数据避免把任意查询故障误当成缺表；不在启动过程中创建表或迁移数据。
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    if ("arte_ai_model_acl_scope".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                return;
            }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT scope_key FROM arte_ai_model_acl_scope LIMIT 1")) {
                if (!rows.next()) {
                    return;
                }
            }
            ModelAclAccess gate = access.getIfAvailable();
            AiResourceEntitlementService service = entitlements.getIfAvailable();
            requireReady(properties, gate, service);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot establish model ACL migration authority", failure);
        }
    }
}
