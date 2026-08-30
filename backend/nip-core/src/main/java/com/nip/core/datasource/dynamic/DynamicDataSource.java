package com.nip.core.datasource.dynamic;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 动态数据源决策
 *
 * @author zhangsc ≧◔◡◔≦
 * @version 1.0.0 ✵
 * @since 2021/2/6 16:09 ✾
 **/
public class DynamicDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DbContextHolder.getDataSourceKey();
    }
}
