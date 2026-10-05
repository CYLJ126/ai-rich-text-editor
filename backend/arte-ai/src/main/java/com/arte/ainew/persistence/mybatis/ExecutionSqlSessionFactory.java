package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.persistence.mybatis.mapper.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 显式创建独立工厂，不参与旧 MapperScan，不继承容器中的 MyBatis-Plus 插件。
 * 与适配器的 Spring 事务管理器使用同一个 DataSource、同一个工作线程。
 * 禁用二级缓存，一级缓存限于单条语句，避免锁查询或数据库时钟被缓存。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public final class ExecutionSqlSessionFactory {
    private ExecutionSqlSessionFactory() { }

    public static SqlSessionFactory create(DataSource dataSource) {
        var configuration = new Configuration(new Environment("ainew",
                new SpringManagedTransactionFactory(), Objects.requireNonNull(dataSource, "dataSource")));
        // 内部快照包含输入及授权信息，避免全局 SQL debug 将参数写入日志。
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setDefaultStatementTimeout(30);
        // 明确列举，防止扩大包扫描时把旧业务插件或 Mapper 引入本存储。
        for (var mapper : new Class<?>[]{SystemMapper.class, ExecutionMapper.class, AdmissionMapper.class,
                BudgetMapper.class, EventMapper.class, OutboxMapper.class}) {
            String path = "ainew/persistence/" + mapper.getSimpleName() + ".xml";
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot load mapper " + path, e);
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
