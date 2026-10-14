package com.arte.app.platform.persistence;

import com.arte.app.platform.persistence.mapper.PlatformAuditMapper;
import com.arte.app.service.platform.AuditSinkServiceImpl;
import com.arte.core.datasource.dynamic.DbContextHolder;
import com.arte.core.pojo.platform.AuditEntry;
import com.arte.core.pojo.platform.CommandScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.UnexpectedRollbackException;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 Mapper 和事务验证必要审计写入，异常分支单独模拟零行结果及 SQL 失败。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
class AuditSinkServiceTest {
    JdbcTemplate jdbc;
    JdbcPublicTransactionRunner tx;
    PlatformAuditMapper mapper;
    AuditSinkServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:audit_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String file : List.of("arte-platform-identity-ddl-mysql.sql", "arte-platform-workspace-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", file))
                    .replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                    .replaceAll("(?i)ENGINE=InnoDB", "")
                    .replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
        var config = new PlatformMybatisConfiguration();
        var properties = new PlatformPersistenceProperties(true, 1, 1, 5, 15, 0,
                null, null, null, null, null, false);
        mapper = config.platformAuditMapper(config.platformMapperSession(source, properties));
        service = new AuditSinkServiceImpl(mapper, tx);
    }

    @Test
    void outsideTransactionIsRejectedWithoutWriting() {
        assertThrows(IllegalStateException.class, () -> service.append(entry("outside", new CommandScope(null, null))));
        assertEquals(0, count());
    }

    @Test
    void persistsAllFieldsAndNullScopesUsingPhysicalAppConnection() {
        var scopes = List.of(new CommandScope(null, null), new CommandScope("tenant", null),
                new CommandScope("tenant", "space"));
        // 专用 Mapper 不读取旧 UserContext，也不沿用调用线程的路由键。
        try {
            DbContextHolder.setDataSourceKey("chlorophyll");
            tx.required(() -> {
                for (int i = 0; i < scopes.size(); i++) service.append(entry("audit-" + i, scopes.get(i)));
                return null;
            });
            assertEquals("chlorophyll", DbContextHolder.getDataSourceKey());
        } finally {
            DbContextHolder.clearDataSourceKey();
        }
        for (int i = 0; i < scopes.size(); i++) {
            var expected = entry("audit-" + i, scopes.get(i));
            var saved = mapper.selectById(expected.id());
            assertEquals(expected.id(), saved.getId());
            assertEquals(expected.actorSubjectId(), saved.getActorSubjectId());
            assertEquals(expected.scope().tenantId(), saved.getTenantId());
            assertEquals(expected.scope().workspaceId(), saved.getWorkspaceId());
            assertEquals(expected.action(), saved.getAction());
            assertEquals(expected.operationId(), saved.getOperationId());
            assertEquals(expected.reason(), saved.getReason());
            assertEquals(expected.occurredAt(), Instant.ofEpochSecond(saved.getOccurredSec(), saved.getOccurredNano()));
        }
        assertEquals(3, count());
    }

    @Test
    void caughtDuplicateAuditRollsBackBothBusinessAndAudit() {
        var audit = entry("duplicate", new CommandScope(null, null));
        tx.required(() -> {
            service.append(audit);
            return null;
        });
        assertThrows(UnexpectedRollbackException.class, () -> tx.required(() -> {
            businessWrite();
            service.append(entry("new-audit", audit.scope()));
            assertThrows(DuplicateKeyException.class, () -> service.append(audit));
            return null;
        }));
        assertNoBusinessCommit();
        assertEquals(1, count());
        assertNull(mapper.selectById("new-audit"));
    }

    @Test
    void zeroRowResultMarksActualTransactionForRollback() {
        var noWrite = insertOnlyMapper(() -> 0);
        var failing = new AuditSinkServiceImpl(noWrite, tx);
        assertThrows(UnexpectedRollbackException.class, () -> tx.required(() -> {
            businessWrite();
            assertThrows(IllegalStateException.class, () -> failing.append(entry("zero", new CommandScope(null, null))));
            return null;
        }));
        assertNoBusinessCommit();
        assertEquals(0, count());
    }

    @Test
    void caughtMapperExceptionMarksActualTransactionForRollback() {
        var failure = new IllegalStateException("database unavailable");
        var broken = insertOnlyMapper(() -> {
            throw failure;
        });
        var failing = new AuditSinkServiceImpl(broken, tx);
        assertThrows(UnexpectedRollbackException.class, () -> tx.required(() -> {
            businessWrite();
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> failing.append(entry("failed", new CommandScope(null, null)))));
            return null;
        }));
        assertNoBusinessCommit();
    }

    @Test
    void caughtConversionFailureAlsoPreventsBusinessCommit() {
        assertThrows(UnexpectedRollbackException.class, () -> tx.required(() -> {
            businessWrite();
            assertThrows(NullPointerException.class, () -> service.append(null));
            return null;
        }));
        assertNoBusinessCommit();
        assertEquals(0, count());
    }

    private AuditEntry entry(String id, CommandScope scope) {
        return new AuditEntry(id, "stable-subject", scope, "workspace:changed", "operation-id",
                "调整成员资格", Instant.ofEpochSecond(1791600000L, 123456000));
    }

    private PlatformAuditMapper insertOnlyMapper(IntSupplier insert) {
        return (PlatformAuditMapper) Proxy.newProxyInstance(PlatformAuditMapper.class.getClassLoader(),
                new Class<?>[]{PlatformAuditMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insert") && method.getParameterCount() == 1) {
                        return insert.getAsInt();
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }

    private void businessWrite() {
        jdbc.update("INSERT INTO arte_platform_tenant VALUES('business','name',1,NULL,0,'actor',0,0,'1970-01-01 00:02:03.000001','1970-01-01 00:02:03.000001')");
    }

    private void assertNoBusinessCommit() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_tenant WHERE id='business'", Integer.class));
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_audit", Integer.class);
    }
}
