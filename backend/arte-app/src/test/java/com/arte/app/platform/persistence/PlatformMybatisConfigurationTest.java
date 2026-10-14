package com.arte.app.platform.persistence;

import com.arte.app.config.MybatisPlusConfig;
import com.arte.app.mapper.rbac.UserMapper;
import com.arte.app.platform.persistence.mapper.PlatformOutboxMapper;
import com.arte.app.platform.persistence.mapper.PlatformTenantMapper;
import com.arte.app.platform.persistence.mapper.PlatformWorkspacePolicyMapper;
import com.arte.app.pojo.platform.*;
import com.arte.core.datasource.dynamic.DbContextHolder;
import com.arte.core.datasource.dynamic.MultipleDataSourceHelper;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Mapper 与 DDL，验证新旧装配共存、数据源隔离、映射及事务连接。
 */
class PlatformMybatisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
            .withUserConfiguration(Database.class, MybatisPlusConfig.class,
                    PlatformMybatisConfiguration.class)
            .withPropertyValues("arte.platform.persistence.enabled=true",
                    "arte.platform.persistence.query-timeout-seconds=7",
                    "mybatis-plus.configuration.local-cache-scope=SESSION",
                    "mybatis-plus.configuration.cache-enabled=true");

    @Test
    void disabledDoesNotRequireDatasourceOrRegisterMappers() {
        new ApplicationContextRunner().withUserConfiguration(PlatformMybatisConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(PlatformTenantMapper.class).size());
                    assertEquals(0, context.getBeansOfType(PlatformMybatisSession.class).size());
                });
    }

    @Test
    void legacyFactoryAndMappersRemainWhilePlatformUsesDedicatedConfiguration() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(UserMapper.class));
            assertEquals(1, context.getBeansOfType(SqlSessionFactory.class).size());
            assertSame(context.getBean("multipleDataSource"), context.getBean(SqlSessionFactory.class)
                    .getConfiguration().getEnvironment().getDataSource());
            var configuration = context.getBean(PlatformMybatisSession.class).factory().getConfiguration();
            assertSame(context.getBean("appDataSource"), configuration.getEnvironment().getDataSource());
            assertFalse(configuration.isCacheEnabled());
            assertEquals(LocalCacheScope.STATEMENT, configuration.getLocalCacheScope());
            assertEquals(7, configuration.getDefaultStatementTimeout());
            assertTrue(configuration.getInterceptors().isEmpty());
            assertEquals(12, configuration.getMapperRegistry().getMappers().size());
            assertTrue(configuration.hasMapper(com.arte.app.platform.persistence.mapper.PlatformPersonalPolicyTemplateMapper.class));
            assertFalse(configuration.hasMapper(UserMapper.class));
        });
    }

    @Test
    void mappersIgnoreThreadRouteAndOldCreatorFilters() {
        runner.run(context -> {
            var mapper = context.getBean(PlatformTenantMapper.class);
            try {
                DbContextHolder.setDataSourceKey("chlorophyll");
                assertEquals("app tenant", mapper.selectById("tenant").getName());
                assertEquals(1, mapper.insert(tenant("new", "platform-only")));
                var app = new JdbcTemplate(context.getBean("appDataSource", DataSource.class));
                var other = new JdbcTemplate(context.getBean("chlorophyllDataSource", DataSource.class));
                assertEquals(1, app.queryForObject("SELECT COUNT(*) FROM arte_platform_tenant WHERE id='new'", Integer.class));
                assertEquals(0, other.queryForObject("SELECT COUNT(*) FROM arte_platform_tenant WHERE id='new'", Integer.class));
                assertEquals("chlorophyll", DbContextHolder.getDataSourceKey());
            } finally {
                DbContextHolder.clearDataSourceKey();
            }
        });
    }

    @Test
    void jdbcAndMapperSharePhysicalTransactionAndBothRollBack() {
        runner.run(context -> {
            var source = context.getBean("appDataSource", DataSource.class);
            var jdbc = new JdbcTemplate(source);
            var mapper = context.getBean(PlatformTenantMapper.class);
            var tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
            assertThrows(IllegalStateException.class, () -> tx.required(() -> {
                assertEquals(1, mapper.insert(tenant("rollback", "created")));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_tenant WHERE id='rollback'", Integer.class));
                jdbc.update("UPDATE arte_platform_tenant SET name='jdbc change' WHERE id='tenant'");
                assertEquals("jdbc change", mapper.selectById("tenant").getName());
                throw new IllegalStateException("abort whole command");
            }));
            assertNull(mapper.selectById("rollback"));
            assertEquals("app tenant", mapper.selectById("tenant").getName());
        });
    }

    @Test
    void repeatedReadsInsideTransactionDoNotUseSessionCache() {
        runner.run(context -> {
            var source = context.getBean("appDataSource", DataSource.class);
            var tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
            var mapper = context.getBean(PlatformTenantMapper.class);
            tx.required(() -> {
                assertEquals(TenantStateEnum.ACTIVE, mapper.selectById("tenant").getState());
                new JdbcTemplate(source).update("UPDATE arte_platform_tenant SET state=1 WHERE id='tenant'");
                assertEquals(TenantStateEnum.SUSPENDED, mapper.selectById("tenant").getState());
                return null;
            });
        });
    }

    @Test
    void enumMicrosecondAndNullableLeaseMappingsRoundTrip() {
        runner.run(context -> {
            var tenants = context.getBean(PlatformTenantMapper.class);
            var value = tenant("precision", "precision");
            assertEquals(1, tenants.insert(value));
            assertEquals(value, tenants.selectById("precision"));
            var outbox = context.getBean(PlatformOutboxMapper.class);
            var event = new PlatformOutboxDto();
            event.setId("event");
            event.setEventType("workspace.changed.v1");
            event.setEvent("{\"id\":\"event\"}");
            event.setOccurredSec(123L);
            event.setOccurredNano(987654321);
            event.setState(1);
            event.setAttempts(1L);
            event.setAvailableAt(LocalDateTime.of(2026, 10, 10, 1, 2, 3, 123456000));
            event.setLeaseToken("lease");
            assertEquals(1, outbox.insert(event));
            assertEquals(event, outbox.selectById("event"));
            event.setState(2);
            event.setAvailableAt(null);
            event.setLeaseToken(null);
            event.setPublishedAt(LocalDateTime.of(2026, 10, 10, 1, 2, 4));
            assertEquals(1, outbox.updateById(event));
            assertEquals(event, outbox.selectById("event"));
        });
    }

    @Test
    void compositePolicyLookupAlwaysIncludesTenantAndWorkspace() {
        runner.run(context -> {
            var jdbc = new JdbcTemplate(context.getBean("appDataSource", DataSource.class));
            jdbc.update("INSERT INTO arte_platform_workspace VALUES('tenant','space','name','',0,'actor',0,0,'1970-01-01 00:02:03.000001','1970-01-01 00:02:03.000001')");
            jdbc.update("INSERT INTO arte_platform_workspace_policy VALUES('tenant','space','{}',1,1,0)");
            var mapper = context.getBean(PlatformWorkspacePolicyMapper.class);
            var policy = mapper.selectByScope("tenant", "space");
            assertEquals("tenant", policy.getTenantId());
            assertEquals("space", policy.getWorkspaceId());
            assertEquals("{}", policy.getRules());
            assertEquals(0L, policy.getVersion());
            assertNull(mapper.selectByScope("other", "space"));
            assertNull(mapper.selectByScope("tenant", "other"));
            assertNull(mapper.selectByScope("tenant' OR '1'='1", "space"));
        });
    }

    @Test
    void allEntityColumnsMatchDeployedDdl() {
        runner.run(context -> {
            var configuration = context.getBean(PlatformMybatisSession.class).factory().getConfiguration();
            var jdbc = new JdbcTemplate(context.getBean("appDataSource", DataSource.class));
            for (Class<?> entity : List.of(PlatformTenantDto.class, PlatformWorkspaceDto.class,
                    PlatformTenantMemberDto.class, PlatformWorkspaceMemberDto.class,
                    PlatformTenantPolicyDto.class, PlatformWorkspacePolicyDto.class,
                    PlatformWorkspacePreferenceDto.class, PlatformCommandDto.class,
                    PlatformOutboxDto.class, PlatformAuditSinkDto.class)) {
                var table = TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                        configuration, "schema-check"), entity);
                Set<String> mapped = new HashSet<>();
                if (table.havePK()) mapped.add(table.getKeyColumn());
                table.getFieldList().forEach(field -> mapped.add(field.getColumn()));
                var actual = new HashSet<>(jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=?",
                        String.class, table.getTableName().toUpperCase(Locale.ROOT)));
                actual = new HashSet<>(actual.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList());
                assertEquals(actual, mapped, entity.getSimpleName());
            }
        });
    }

    private static PlatformTenantDto tenant(String id, String name) {
        var value = new PlatformTenantDto();
        value.setId(id);
        value.setName(name);
        value.setType(TenantTypeEnum.ORGANIZATION);
        value.setState(TenantStateEnum.ACTIVE);
        value.setCreatedBy("actor");
        value.setVersion(0L);
        value.setAuthEpoch(0L);
        value.setCreatedAt(LocalDateTime.of(2026, 10, 14, 1, 2, 3, 123456000));
        value.setUpdatedAt(LocalDateTime.of(2026, 10, 14, 1, 2, 3, 987654000));
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    static class Database {
        @Bean(name = "appDataSource")
        DataSource app() throws Exception {
            return database("app tenant");
        }

        @Bean(name = "chlorophyllDataSource")
        DataSource other() throws Exception {
            return database("other tenant");
        }

        @Bean(name = "multipleDataSource")
        @Primary
        DataSource route(@Qualifier("appDataSource") DataSource app,
                         @Qualifier("chlorophyllDataSource") DataSource other) {
            return MultipleDataSourceHelper.multipleDataSource(Map.of("app", app, "chlorophyll", other), app);
        }

        private DataSource database(String name) throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:platform_mapper_"
                    + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            for (String file : List.of("arte-platform-identity-ddl-mysql.sql", "arte-platform-workspace-ddl-mysql.sql")) {
                var sql = Files.readString(Path.of("scripts", file))
                        .replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                        .replaceAll("(?i)ENGINE=InnoDB", "")
                        .replaceAll("(?im)^SET\\s+[^;]*;", "");
                try (var connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                }
            }
            new JdbcTemplate(source).update("INSERT INTO arte_platform_tenant VALUES('tenant',?,1,NULL,0,'actor',0,0,'1970-01-01 00:02:03.000001','1970-01-01 00:02:03.000001')", name);
            return source;
        }
    }
}
