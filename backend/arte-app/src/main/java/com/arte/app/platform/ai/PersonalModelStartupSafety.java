package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.model.PersonalModelRuntime;
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
 * 已存在个人发布时必须具备完整运行门控
 * <p>
 * 关闭开关或误装旧解析器不能静默绕过。启动只检查，不建表。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class PersonalModelStartupSafety implements SmartInitializingSingleton {

    private final DataSource source;
    private final NewAiProperties properties;
    private final ObjectProvider<PersonalModelRuntime> runtime;
    private final ObjectProvider<ModelAclAccess> acl;
    private final ObjectProvider<AiResourceEntitlementService> entitlements;
    private final ObjectProvider<ConnectionCredentialResolver> credentials;

    public PersonalModelStartupSafety(@Qualifier("appDataSource") DataSource source, NewAiProperties properties,
                                      ObjectProvider<PersonalModelRuntime> runtime, ObjectProvider<ModelAclAccess> acl,
                                      ObjectProvider<AiResourceEntitlementService> entitlements, ObjectProvider<ConnectionCredentialResolver> credentials) {
        this.source = source;
        this.properties = properties;
        this.runtime = runtime;
        this.acl = acl;
        this.entitlements = entitlements;
        this.credentials = credentials;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (Connection connection = source.getConnection()) {
            boolean present = false;
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    if ("arte_ai_model_personal_publication".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                if (runtime.getIfAvailable() != null) {
                    throw new IllegalStateException("Personal model publication schema is required");
                }
                return;
            }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT binding_key FROM arte_ai_model_personal_publication LIMIT 1")) {
                if (!rows.next() && runtime.getIfAvailable() == null) {
                    return;
                }
            }
            if (!"DATABASE".equals(properties.configurationMode()) || !(runtime.getIfAvailable() instanceof PersonalModelRuntimeAdapter)
                    || !(credentials.getIfAvailable() instanceof PersonalModelCredentialResolver)) {
                throw new IllegalStateException("Stored personal models require enabled personal publication, DATABASE control and personal credential resolver");
            }
            ModelAclStartupSafety.requireReady(properties, acl.getIfAvailable(), entitlements.getIfAvailable());
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot establish personal model publication authority", failure);
        }
    }
}
