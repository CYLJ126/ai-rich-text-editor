package com.arte.ainew.persistence;

import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.ExecutionSqlSessionFactory;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.mapper.BudgetMapper;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 验证独立工厂的插件隔离、缓存策略以及 Spring 管理的跨 Mapper 事务。 */
public class ExecutionSqlSessionFactoryTest {
    private JdbcDataSource dataSource;
    @Before public void setup() {
        dataSource=new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
    }
    @After public void cleanup() { new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS"); }

    @Test public void constructingPersistenceDoesNotAccessDatabaseOrExecuteDdl() {
        var guarded = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() {
                throw new AssertionError("Construction must not connect to the database or execute DDL");
            }
            @Override public Connection getConnection(String username, String password) {
                throw new AssertionError("Construction must not connect to the database or execute DDL");
            }
        };
        assertNotNull(ExecutionSqlSessionFactory.create(guarded));
        assertNotNull(new MybatisExecutionPersistence(guarded,
                new JacksonExecutionRecordCodec(),
                Schedulers.immediate()));
    }

    @Test public void factoryIsExplicitAndContainsOnlyNewMappersWithoutLegacyPlugins() {
        var config=ExecutionSqlSessionFactory.create(dataSource).getConfiguration();
        assertSame(dataSource,config.getEnvironment().getDataSource());
        assertTrue(config.getEnvironment().getTransactionFactory() instanceof SpringManagedTransactionFactory);
        assertTrue(config.getInterceptors().isEmpty());
        assertFalse(config.isCacheEnabled());
        assertEquals(LocalCacheScope.STATEMENT,config.getLocalCacheScope());
        assertEquals(ExecutorType.SIMPLE,config.getDefaultExecutorType());
        assertEquals(Integer.valueOf(30),config.getDefaultStatementTimeout());
        assertEquals(6,config.getMapperRegistry().getMappers().size());
        assertTrue(config.getMapperRegistry().getMappers().stream().allMatch(type -> type.getPackageName().equals("com.arte.ainew.persistence.mybatis.mapper")));
        assertTrue(config.getMappedStatements().stream().allMatch(statement -> statement.getCache()==null));
    }
    @Test public void differentMappersJoinTheSameSpringTransactionAndRollbackTogether() {
        var sessions=new SqlSessionTemplate(ExecutionSqlSessionFactory.create(dataSource));
        var system=sessions.getMapper(SystemMapper.class);
        var budget=sessions.getMapper(BudgetMapper.class);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class,()->transaction.execute(status -> {
            system.ensureLock("a".repeat(64));
            budget.insertAccount("b".repeat(64),"c".repeat(64),"snapshot");
            throw new IllegalStateException("injected cross-mapper failure");
        }));
        var jdbc=new JdbcTemplate(dataSource);
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_lock",Long.class));
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account",Long.class));
        transaction.executeWithoutResult(status -> {
            system.ensureLock("a".repeat(64));
            budget.insertAccount("b".repeat(64),"c".repeat(64),"snapshot");
        });
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account",Long.class));
    }
    @Test public void repeatedClockAndLockReadsWithinOneTransactionReachJdbcEveryTime() {
        var reads=new AtomicInteger();
        var instrumented=new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.startsWith("SELECT")) reads.incrementAndGet();
                    try { return method.invoke(connection,args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }
        };
        var system=new SqlSessionTemplate(ExecutionSqlSessionFactory.create(instrumented)).getMapper(SystemMapper.class);
        new TransactionTemplate(new DataSourceTransactionManager(instrumented)).executeWithoutResult(status -> {
            system.ensureLock("a".repeat(64));
            assertNotNull(system.databaseTime()); assertNotNull(system.databaseTime());
            assertEquals("a".repeat(64),system.lock("a".repeat(64)));
            assertEquals("a".repeat(64),system.lock("a".repeat(64)));
        });
        assertEquals(4,reads.get());
    }
}
