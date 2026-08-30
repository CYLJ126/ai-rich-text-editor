package com.nip.core.datasource.dynamic;

import jakarta.annotation.Nonnull;

import javax.sql.DataSource;
import java.util.Map;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/25 20:20 ✾
 */
public class MultipleDataSourceHelper {

    /**
     * 创建动态数据源
     *
     * @param dataSourceMap     <String 键名, DataSource 数据源>
     * @param defaultDataSource 默认数据源
     * @return 动态数据源
     */
    public static DataSource multipleDataSource(Map<Object, Object> dataSourceMap, @Nonnull DataSource defaultDataSource) {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        dynamicDataSource.setTargetDataSources(dataSourceMap);
        dynamicDataSource.setDefaultTargetDataSource(defaultDataSource);
        dynamicDataSource.afterPropertiesSet();
        return dynamicDataSource;
    }
}
