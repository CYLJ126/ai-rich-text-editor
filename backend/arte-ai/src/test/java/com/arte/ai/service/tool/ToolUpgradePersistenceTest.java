package com.arte.ai.service.tool;

import com.arte.ai.mapper.tool.ToolBindingMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolUpgradePersistenceTest {
    @Test
    public void declaringCompatibilityCannotModifyPublishedDefinitionOrPublicationTime() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolVersionMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        var sql = configuration.getMappedStatement(ToolVersionMapper.class.getName() + ".declareCompatibility")
                .getBoundSql(Map.of("id", 2L, "expectedVersion", 1L, "compatibilityBaseVersion", "1.0.0", "releaseNotes", "兼容升级"));
        assertTrue(sql.getSql().contains("compatibility_base_version IS NULL"));
        assertTrue(sql.getSql().contains("lifecycle_state = 'published'"));
        assertTrue(sql.getSql().contains("row_version = ?"));
        for (String immutable : java.util.List.of("input_schema", "risk_profile", "default_policy", "checksum", "published_at")) {
            assertFalse(sql.getSql().contains(immutable));
        }
    }

    @Test
    public void bindingVersionChoiceIsSavedWithConfigurationAndOptimisticLock() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolBindingMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        var binding = new ToolBindingPo().setBindingId("stable-id").setToolVersion("1.0.1").setVersionPolicy("pinned")
                .setConfiguration(Map.of("locale", "zh")).setPolicyOverride(Map.of()).setCredentialReference("reference").setEnabled(true);
        var sql = configuration.getMappedStatement(ToolBindingMapper.class.getName() + ".updateWithVersion")
                .getBoundSql(Map.of("binding", binding, "expectedVersion", 3L)).getSql();
        assertTrue(sql.contains("tool_version"));
        assertTrue(sql.contains("version_policy"));
        assertTrue(sql.contains("configuration"));
        assertTrue(sql.contains("credential_reference"));
        assertTrue(sql.contains("binding_id = ?"));
        assertTrue(sql.contains("row_version = ?"));
        assertFalse(sql.contains("assistant"));
    }
}
