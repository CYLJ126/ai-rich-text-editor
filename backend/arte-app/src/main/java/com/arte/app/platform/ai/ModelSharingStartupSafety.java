package com.arte.app.platform.ai;

import com.arte.ainew.spi.model.ModelSharingStore;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 已登记租户模型不能关闭范围及凭据用途门控；启动只检查，不自动迁移。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class ModelSharingStartupSafety implements SmartInitializingSingleton {

    private final DataSource source;
    private final ObjectProvider<ModelSharingStore> sharing;

    public ModelSharingStartupSafety(@Qualifier("appDataSource") DataSource source, ObjectProvider<ModelSharingStore> sharing) {
        this.source = source;
        this.sharing = sharing;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (Connection connection = source.getConnection()) {
            Set<String> tables = new HashSet<>();
            try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"})) {
                while (rows.next()) {
                    tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            boolean enabled = sharing.getIfAvailable() != null;
            if (enabled && (!tables.contains("arte_ai_model_sharing") || !tables.contains("arte_ai_model_use_scope"))) {
                throw new IllegalStateException("Tenant model sharing schema is required");
            }
            if (tables.contains("arte_ai_model_sharing")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT resource_key FROM arte_ai_model_sharing LIMIT 1")) {
                    if (rows.next() && !enabled) {
                        throw new IllegalStateException("Stored tenant models require enabled sharing gates");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot establish tenant model sharing authority", failure);
        }
    }
}
