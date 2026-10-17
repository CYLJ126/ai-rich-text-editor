package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.persistence.mybatis.mapper.SearchReadMapper;
import com.arte.ainew.persistence.mybatis.mapper.SearchSourceMapper;
import com.arte.ainew.persistence.mybatis.mapper.SearchSyncMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;

/**
 * 搜索适配器单独注册扫描 Mapper，不改变执行存储的 Mapper 集合。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchSqlSessionFactory {

    private SearchSqlSessionFactory() {
    }

    public static SqlSessionFactory create(DataSource dataSource) {
        return create(dataSource, null);
    }

    /**
     * 后台源读取和进度存储传入独立日志策略；前台搜索读取沿用原诊断。
     */
    public static SqlSessionFactory create(DataSource dataSource, NewAiSearchProperties.Diagnostics diagnostics) {
        var factory = diagnostics == null ? ExecutionSqlSessionFactory.create(dataSource)
                : ExecutionSqlSessionFactory.create(dataSource, new ExecutionSqlDiagnostics(diagnostics));
        for (var type : new Class<?>[]{SearchSourceMapper.class, SearchSyncMapper.class, SearchReadMapper.class}) {
            String path = "ainew/persistence/" + type.getSimpleName() + ".xml";
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, factory.getConfiguration(), path, factory.getConfiguration().getSqlFragments()).parse();
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot load search mapper", error);
            }
        }
        return factory;
    }
}
