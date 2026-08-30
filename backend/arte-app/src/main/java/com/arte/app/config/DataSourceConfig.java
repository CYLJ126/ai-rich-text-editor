package com.arte.app.config;

import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceBuilder;
import com.arte.core.datasource.dynamic.MultipleDataSourceHelper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源配置
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/25 20:53 ✾
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.druid.app")
    public DataSource appDataSource() {
        return DruidDataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.druid.chlorophyll")
    public DataSource chlorophyllDataSource() {
        return DruidDataSourceBuilder.create().build();
    }

    @Bean
    @Primary
    public DataSource multipleDataSource() {
        Map<Object, Object> dataSourceMap = new HashMap<>(2);
        dataSourceMap.put("app", appDataSource());
        dataSourceMap.put("chlorophyll", chlorophyllDataSource());
        return MultipleDataSourceHelper.multipleDataSource(dataSourceMap, appDataSource());
    }
}
