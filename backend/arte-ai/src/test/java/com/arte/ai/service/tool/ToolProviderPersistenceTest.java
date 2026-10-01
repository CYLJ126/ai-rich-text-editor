package com.arte.ai.service.tool;

import com.arte.ai.mapper.tool.ToolProviderMapper;
import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class ToolProviderPersistenceTest {

    @Test
    public void shouldWriteSqlNullWhenClearingPreviousSynchronizationError() {
        ToolProviderPo provider = new ToolProviderPo().setLastError("previous synchronization failed");
        provider.setId(1L);
        provider.setLastError(null);

        BoundSql update = updateSql(provider);

        assertTrue(update.getSql().matches("(?s).*last_error\\s*=\\s*\\?.*"));
        assertTrue(update.getParameterMappings().stream()
                .anyMatch(parameter -> (Constants.ENTITY + ".lastError").equals(parameter.getProperty())));
        assertNull(provider.getLastError());
    }

    @Test
    public void shouldStillPersistCurrentSynchronizationFailure() {
        ToolProviderPo provider = new ToolProviderPo().setLastError("a current synchronization failure");
        provider.setId(1L);

        BoundSql update = updateSql(provider);

        assertTrue(update.getSql().matches("(?s).*last_error\\s*=\\s*\\?.*"));
        assertEquals("a current synchronization failure", provider.getLastError());
    }

    private BoundSql updateSql(ToolProviderPo provider) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ToolProviderMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        return configuration.getMappedStatement(ToolProviderMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of(Constants.ENTITY, provider));
    }
}
