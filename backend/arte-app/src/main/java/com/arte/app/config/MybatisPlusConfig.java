package com.arte.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.arte.core.interceptor.MybatisInsertInterceptor;
import com.arte.core.interceptor.MybatisQueryInterceptor;
import com.arte.core.interceptor.MybatisUpdateInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MybatisPlus 配置
 * 如需其他拦截器，在此进行添加
 *
 * @author zhangsc
 * @since 2025/1/15 11:48
 */
@Configuration
@EnableTransactionManagement
@MapperScan({"com.arte.app.mapper", "com.arte.ai.mapper"})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 查询参数插件
        interceptor.addInnerInterceptor(new MybatisQueryInterceptor());
        // 插入参数插件
        interceptor.addInnerInterceptor(new MybatisInsertInterceptor());
        // 更新参数插件
        interceptor.addInnerInterceptor(new MybatisUpdateInterceptor());
        // 乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
