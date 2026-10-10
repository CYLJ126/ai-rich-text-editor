package com.arte.app.platform.identity;

import com.arte.app.mapper.rbac.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 复用 UserMapper 的身份查询，固定绑定 app 物理库，与身份 Store 共用事务连接。
 * 原 userMapper 继续由既有扫描器装配，供用户管理及其路由事务使用。
 */
@Configuration(proxyBeanMethods = false)
public class RbacAccountMapperConfiguration {

    /**
     * 仅通过限定名称注入，避免影响旧 UserService 的默认 Mapper 选择。
     * 工厂和模板保留在此 Mapper 内，不作为全局 Bean 覆盖 MyBatis 自动配置。
     * 不添加业务查询拦截器；身份核验不受创建人过滤或线程路由影响。
     */
    @Bean(name = "identityUserMapper", defaultCandidate = false)
    public UserMapper identityUserMapper(@Qualifier("appDataSource") DataSource dataSource) {
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("rbac-identity",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setDefaultStatementTimeout(30);
        configuration.addMapper(UserMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(UserMapper.class);
    }
}
