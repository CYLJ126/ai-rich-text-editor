package com.arte.app.platform.identity;

import com.arte.app.config.MybatisPlusConfig;
import com.arte.app.mapper.rbac.UserMapper;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.app.pojo.rbac.param.UserParam;
import com.arte.app.service.rbac.UserServiceImpl;
import com.arte.app.web.aspect.DataSourceSwitchAspect;
import com.arte.core.datasource.dynamic.DbContextHolder;
import com.arte.core.datasource.dynamic.MultipleDataSourceHelper;
import com.arte.core.enums.StatusEnum;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Mapper、路由库和事务装配，核验复用不改变旧用户管理，也不削弱身份读取。
 */
class RbacAccountReaderTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
            .withUserConfiguration(Database.class, MybatisPlusConfig.class,
                    RbacAccountMapperConfiguration.class, RbacAccountReader.class)
            .withPropertyValues("mybatis-plus.configuration.local-cache-scope=SESSION",
                    "mybatis-plus.configuration.cache-enabled=true");

    @Test
    void oldMapperRemainsDefaultAndItsCrudUsesOriginalRoutingTransaction() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var route = context.getBean("multipleDataSource", DataSource.class);
            var app = context.getBean("appDataSource", DataSource.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            assertSame(context.getBean("userMapper"), users);
            assertNotSame(context.getBean("identityUserMapper"), users);
            assertEquals(1, context.getBeansOfType(SqlSessionFactory.class).size());
            assertSame(route, context.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource());
            var tx = new TransactionTemplate(new DataSourceTransactionManager(route));
            tx.executeWithoutResult(status -> {
                assertEquals("alice", users.selectById(1).getUserName());
                assertEquals(1, users.deleteById(1));
                assertNull(users.selectById(1));
                status.setRollbackOnly();
            });
            assertEquals(1, new JdbcTemplate(app).queryForObject("SELECT COUNT(*) FROM arte_rbac_user", Integer.class));
        });
    }

    @Test
    void identityReadsPhysicalAppEvenWhenThreadRoutesToAnotherDatabase() {
        runner.run(context -> {
            var reader = context.getBean(RbacAccountReader.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            try {
                DbContextHolder.setDataSourceKey("chlorophyll");
                assertEquals(StatusEnum.CLOSED, users.selectIdentityById(1).getFirst().getStatus());
                var account = reader.findById(1L).orElseThrow();
                assertEquals("alice", account.name());
                assertTrue(account.active());
                assertEquals(LocalDateTime.of(2026, 10, 10, 10, 0), account.createdAt());
                assertTrue(reader.findByName("alice' OR '1'='1").isEmpty());
            } finally {
                DbContextHolder.clearDataSourceKey();
            }
        });
    }

    @Test
    void realDatasourceAspectSkipsIdentityMapperAndPreservesCallerRoute() {
        runner.withUserConfiguration(RoutingAspect.class).run(context -> {
            var reader = context.getBean(RbacAccountReader.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            try {
                DbContextHolder.setDataSourceKey("chlorophyll");
                assertTrue(reader.findById(1L).orElseThrow().active());
                assertEquals("chlorophyll", DbContextHolder.getDataSourceKey());
                var tx = new TransactionTemplate(new DataSourceTransactionManager(
                        context.getBean("appDataSource", DataSource.class)));
                tx.executeWithoutResult(status -> {
                    assertTrue(reader.findCurrentById(1).orElseThrow().active());
                    assertEquals("chlorophyll", DbContextHolder.getDataSourceKey());
                });
                // 旧 Mapper 仍按原切面切到 app 并清理路由。
                assertEquals(StatusEnum.DOING, users.selectIdentityById(1).getFirst().getStatus());
                assertNull(DbContextHolder.getDataSourceKey());
            } finally {
                DbContextHolder.clearDataSourceKey();
            }
        });
    }

    @Test
    void identityQueriesBypassExistingSessionCacheAndDoNotLoadPasswords() {
        runner.run(context -> {
            var route = context.getBean("multipleDataSource", DataSource.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            var tx = new TransactionTemplate(new DataSourceTransactionManager(route));
            tx.executeWithoutResult(status -> {
                var first = users.selectIdentityById(1).getFirst();
                assertEquals(StatusEnum.DOING, first.getStatus());
                assertNull(first.getPassword());
                // JDBC 更新不经过 MyBatis 的缓存失效逻辑；同一 SqlSession 的第二次读取仍须执行 SQL。
                new JdbcTemplate(route).update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
                assertEquals(StatusEnum.CLOSED, users.selectIdentityById(1).getFirst().getStatus());
                status.setRollbackOnly();
            });
        });
    }

    @Test
    void loginXmlReadsCurrentCredentialsAndProfileWithoutSessionCache() {
        runner.run(context -> {
            var route = context.getBean("multipleDataSource", DataSource.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            var jdbc = new JdbcTemplate(route);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(route));
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE arte_rbac_user SET mobile='new-mobile',email='new-email' WHERE id=1");
                var first = users.selectLoginByName("alice").getFirst();
                assertEquals("sensitive-hash", first.getPassword());
                assertEquals("new-mobile", first.getMobile());
                assertEquals("new-email", first.getEmail());
                assertEquals(LocalDateTime.of(2026, 10, 10, 10, 0), first.getCreateTime());
                // 绕过 MyBatis 更新，让登录 SQL 自身承担查询缓存失效。
                jdbc.update("UPDATE arte_rbac_user SET password='changed-hash',status=3 WHERE id=1");
                var second = users.selectLoginByName("alice").getFirst();
                assertEquals("changed-hash", second.getPassword());
                assertEquals(StatusEnum.CLOSED, second.getStatus());
                assertTrue(users.selectLoginByName("alice' OR '1'='1").isEmpty());
                assertNull(users.selectIdentityById(1).getFirst().getPassword());
                status.setRollbackOnly();
            });
        });
    }

    @Test
    void longAccountIdSupportsMapperCrudLockedReadAndLogin() {
        runner.run(context -> {
            long id = Long.MAX_VALUE;
            var app = context.getBean("appDataSource", DataSource.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            var reader = context.getBean(RbacAccountReader.class);
            new JdbcTemplate(app).update("INSERT INTO arte_rbac_user(id,user_name,status,create_time,password) "
                    + "VALUES(?,'large-account',1,'2026-10-10 10:00:00','large-hash')", id);
            assertEquals(id, users.selectById(id).getId());
            assertEquals(id, reader.findById(id).orElseThrow().id());
            new TransactionTemplate(new DataSourceTransactionManager(app)).executeWithoutResult(status ->
                    assertEquals(id, reader.findCurrentById(id).orElseThrow().id()));
            var service = new LoginUserService(id);
            ReflectionTestUtils.setField(service, "baseMapper", users);
            var session = ((JwtUserDto) service.loadUserByUsername("large-account")).userOnlineInfo();
            assertEquals(id, session.getId());
            assertEquals("large-hash", session.getPassword());
            var route = context.getBean("multipleDataSource", DataSource.class);
            new TransactionTemplate(new DataSourceTransactionManager(route)).executeWithoutResult(status -> {
                assertEquals(1, users.deleteById(id));
                assertNull(users.selectById(id));
                status.setRollbackOnly();
            });
            assertEquals(id, users.selectById(id).getId());
        });
    }

    @Test
    void loginSuspendsBoundForeignRoutingTransactionAndRestoresItsConnection() {
        runner.withUserConfiguration(RoutingAspect.class).run(context -> {
            var route = context.getBean("multipleDataSource", DataSource.class);
            var other = context.getBean("chlorophyllDataSource", DataSource.class);
            var users = context.getBean(DefaultUserConsumer.class).users;
            new JdbcTemplate(other).update("UPDATE arte_rbac_user SET status=1,password='foreign-hash' WHERE id=1");
            var manager = new DataSourceTransactionManager(route);
            var tx = new TransactionTemplate(manager);
            var service = new LoginUserService();
            ReflectionTestUtils.setField(service, "baseMapper", users);
            var proxyFactory = new ProxyFactory();
            proxyFactory.setTarget(service);
            proxyFactory.setInterfaces(UserDetailsService.class);
            proxyFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var login = (UserDetailsService) proxyFactory.getProxy();
            try {
                DbContextHolder.setDataSourceKey("chlorophyll");
                tx.executeWithoutResult(status -> {
                    var connection = DataSourceUtils.getConnection(route);
                    // 已绑定其他库时，普通 Mapper 的 app 切面不能更换事务连接。
                    assertEquals("foreign-hash", users.selectLoginByName("alice").getFirst().getPassword());
                    var details = (JwtUserDto) login.loadUserByUsername("alice");
                    assertEquals("sensitive-hash", details.getPassword());
                    assertEquals("tag:list", details.getAuthorities().iterator().next().getAuthority());
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertSame(connection, DataSourceUtils.getConnection(route));
                    assertEquals("foreign-hash", new JdbcTemplate(route).queryForObject(
                            "SELECT password FROM arte_rbac_user WHERE id=1", String.class));
                    status.setRollbackOnly();
                });
            } finally {
                DbContextHolder.clearDataSourceKey();
            }
        });
    }

    @Test
    void lockIsHeldUntilPhysicalCallerTransactionEnds() {
        runner.run(context -> {
            var app = context.getBean("appDataSource", DataSource.class);
            var reader = context.getBean(RbacAccountReader.class);
            var jdbc = new JdbcTemplate(app);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(app));
            assertThrows(IllegalStateException.class, () -> reader.findCurrentById(1));
            try (var pool = Executors.newSingleThreadExecutor()) {
                tx.executeWithoutResult(status -> {
                    assertTrue(reader.findCurrentById(1).orElseThrow().active());
                    var contender = pool.submit(() -> assertThrows(DataAccessException.class,
                            () -> jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1")));
                    assertDoesNotThrow(() -> contender.get(5, TimeUnit.SECONDS));
                });
                assertEquals(1, jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1"));
                assertFalse(reader.findById(1L).orElseThrow().active());
            }
        });
    }

    @Test
    void missingAndUnknownStatusesNeverGrantAnActiveAccount() {
        runner.run(context -> {
            var app = context.getBean("appDataSource", DataSource.class);
            var reader = context.getBean(RbacAccountReader.class);
            var jdbc = new JdbcTemplate(app);
            jdbc.update("UPDATE arte_rbac_user SET status=NULL WHERE id=1");
            assertFalse(reader.findById(1L).orElseThrow().active());
            jdbc.update("UPDATE arte_rbac_user SET status=99 WHERE id=1");
            assertFalse(reader.findById(1L).orElseThrow().active());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Database {
        @Bean(name = "appDataSource")
        DataSource app() {
            return source(1);
        }

        @Bean(name = "chlorophyllDataSource")
        DataSource other() {
            return source(3);
        }

        @Bean(name = "multipleDataSource")
        @Primary
        DataSource route(@Qualifier("appDataSource") DataSource appDataSource,
                         @Qualifier("chlorophyllDataSource") DataSource chlorophyllDataSource) {
            return MultipleDataSourceHelper.multipleDataSource(
                    Map.of("app", appDataSource, "chlorophyll", chlorophyllDataSource), appDataSource);
        }

        @Bean
        DefaultUserConsumer consumer(UserMapper users) {
            return new DefaultUserConsumer(users);
        }

        private DataSource source(int status) {
            var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=200", "sa", "");
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE arte_rbac_user(id BIGINT PRIMARY KEY, user_name VARCHAR(64), status INT, "
                    + "create_time TIMESTAMP, update_time TIMESTAMP, create_by VARCHAR(64), update_by VARCHAR(64), "
                    + "mobile VARCHAR(64), email VARCHAR(64), password VARCHAR(64), description VARCHAR(256), row_version INT)");
            jdbc.update("INSERT INTO arte_rbac_user(id,user_name,status,create_time,password) "
                    + "VALUES(1,'alice',?,'2026-10-10 10:00:00','sensitive-hash')", status);
            return source;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @Import(DataSourceSwitchAspect.class)
    static class RoutingAspect {
    }

    /**
     * 只替代权限缓存，登录方法、Mapper、事务通知和数据源切面均执行实际代码。
     */
    private static class LoginUserService extends UserServiceImpl {
        private final long accountId;

        LoginUserService() {
            this(1L);
        }

        LoginUserService(long accountId) {
            this.accountId = accountId;
        }

        @Override
        public UserDto getByName(String name) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var cached = new UserDto();
            cached.setId(accountId);
            return cached;
        }

        @Override
        public Collection<String> getRoles(UserParam user) {
            return List.of("USER");
        }

        @Override
        public Collection<String> getMenus(UserParam user) {
            return List.of("HomePage");
        }

        @Override
        public Collection<String> getAuthorities(UserParam user) {
            return List.of("tag:list");
        }

        @Override
        public Collection<String> getDepartments(UserParam user) {
            return List.of();
        }

        @Override
        public Collection<String> getGroups(UserParam user) {
            return List.of();
        }
    }

    private record DefaultUserConsumer(UserMapper users) {
    }
}
