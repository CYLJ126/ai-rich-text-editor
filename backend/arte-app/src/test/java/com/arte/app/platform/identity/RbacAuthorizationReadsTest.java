package com.arte.app.platform.identity;

import com.alibaba.druid.filter.FilterAdapter;
import com.alibaba.druid.filter.FilterChain;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.proxy.jdbc.PreparedStatementProxy;
import com.alibaba.druid.proxy.jdbc.ResultSetProxy;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.UserOnlineInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 JDBC 验证授权读取减少，以及当前读、事务挂起和批次清理的安全边界。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 17:46 ✾
 */
class RbacAuthorizationReadsTest {
    private final List<String> queries = new ArrayList<>();
    private DruidDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RbacIdentityProvider provider;
    private RbacToolPermissionReader permissions;
    private Authentication login;
    private String subject;

    @BeforeEach
    void setup() throws Exception {
        source = new DruidDataSource();
        source.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        source.setUsername("sa");
        source.setPassword("");
        source.setMaxActive(4);
        source.setTestWhileIdle(false);
        source.setProxyFilters(List.of(new FilterAdapter() {
            @Override
            public boolean preparedStatement_execute(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
                queries.add(statement.getSql());
                return chain.preparedStatement_execute(statement);
            }

            @Override
            public ResultSetProxy preparedStatement_executeQuery(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
                queries.add(statement.getSql());
                return chain.preparedStatement_executeQuery(statement);
            }
        }));
        source.init();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE arte_rbac_user(id BIGINT PRIMARY KEY,user_name VARCHAR(64) UNIQUE,status INT,create_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE arte_platform_principal_identity(subject_id VARCHAR(256) PRIMARY KEY,provider VARCHAR(96),"
                + "external_user_id VARCHAR(64),external_created_at TIMESTAMP NOT NULL,created_at TIMESTAMP NOT NULL,retired_at TIMESTAMP,"
                + "version BIGINT NOT NULL,UNIQUE(provider,external_user_id))");
        jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(64),binding_type VARCHAR(32),target VARCHAR(64),UNIQUE(source,binding_type,target))");
        jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(64) PRIMARY KEY,status VARCHAR(4))");
        jdbc.update("INSERT INTO arte_rbac_user VALUES(1,'alice',1,?)", LocalDateTime.parse("2026-10-10T10:00:00"));
        jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiTool:invoke')");
        var accounts = new RbacAccountReader(source, new RbacAccountMapperConfiguration().identityUserMapper(source));
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        provider = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(source, accounts), tx,
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, Clock.systemUTC());
        permissions = new RbacToolPermissionReader(source);
        subject = provider.resolveAuthenticatedName("alice").orElseThrow().subjectId();
        login = UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(new UserOnlineInfo().setId(1L).setUserName("alice")
                .setAccountCreatedAt(LocalDateTime.parse("2026-10-10T10:00:00"))), null, List.of());
        queries.clear();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
        source.close();
    }

    @Test
    void sharedRawFactsReduceSqlButStillCheckSessionAndExecutionEvidence() {
        tx.executeWithoutResult(status -> repeatedReads());
        int original = queries.size();
        queries.clear();
        tx.executeWithoutResult(status -> provider.duringAuthorization(() -> {
            repeatedReads();
            var wrongName = new ExecutionPrincipal(subject, "renamed", ExecutionPrincipal.Kind.USER);
            assertTrue(provider.findCurrentExecutionPrincipal(wrongName).isEmpty());
            var oldSession = UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(new UserOnlineInfo().setId(1L)
                    .setUserName("alice").setAccountCreatedAt(LocalDateTime.parse("2026-10-11T10:00:00"))), null, List.of());
            assertThrows(RuntimeException.class, () -> provider.actorCurrent(oldSession));
            return true;
        }));
        assertTrue(queries.size() < original, "Authorization SQL must decrease");
        assertEquals(6, queries.size(), "Two snapshot, two current and two direct RBAC queries");
        assertEquals(1, queries.stream().filter(sql -> sql.contains("arte_rbac_user") && sql.contains("FOR UPDATE")).count());
        assertEquals(1, queries.stream().filter(sql -> sql.contains("arte_platform_principal_identity") && sql.contains("FOR UPDATE")).count());
    }

    private void repeatedReads() {
        assertEquals(subject, provider.actor(login).principal().subjectId());
        assertEquals(PrincipalStatusEnum.ACTIVE, provider.findBySubjectId(subject).orElseThrow().status());
        assertEquals(PrincipalStatusEnum.ACTIVE, provider.findCurrentBySubjectId(subject).orElseThrow().status());
        assertEquals(subject, provider.actorCurrent(login).principal().subjectId());
        assertEquals(subject, provider.findCurrentExecutionPrincipal(new ExecutionPrincipal(subject, "alice", ExecutionPrincipal.Kind.USER)).orElseThrow().subjectId());
        assertTrue(permissions.current("alice").contains("aiTool:invoke"));
        assertTrue(permissions.current("alice").contains("aiTool:invoke"));
    }

    @Test
    void snapshotDoesNotReplaceCurrentLockedRead() {
        tx.executeWithoutResult(status -> provider.duringAuthorization(() -> {
            provider.findBySubjectId(subject).orElseThrow();
            assertEquals(2, queries.size());
            provider.findCurrentBySubjectId(subject).orElseThrow();
            assertEquals(4, queries.size());
            assertTrue(queries.subList(2, 4).stream().allMatch(sql -> sql.contains("FOR UPDATE")));
            return true;
        }));
    }

    @Test
    void nextAuthorizationInSameTransactionSeesRevocationAndLifecycleChanges() {
        tx.executeWithoutResult(status -> {
            provider.duringAuthorization(() -> {
                assertTrue(permissions.current("alice").contains("aiTool:invoke"));
                return provider.actorCurrent(login);
            });
            jdbc.update("DELETE FROM arte_rbac_relation WHERE source='alice'");
            jdbc.update("UPDATE arte_rbac_user SET create_time='2026-10-11 00:00:00' WHERE id=1");
            provider.duringAuthorization(() -> {
                assertTrue(permissions.current("alice").isEmpty());
                assertThrows(RuntimeException.class, () -> provider.actorCurrent(login));
                assertThrows(RuntimeException.class, () -> provider.findCurrentBySubjectId(subject));
                return true;
            });
        });
    }

    @Test
    void suspendedTransactionFactsAreNotVisibleToRequiresNewAndResumeCorrectly() {
        var nested = new TransactionTemplate(tx.getTransactionManager());
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> provider.duringAuthorization(() -> {
            provider.findBySubjectId(subject).orElseThrow();
            assertEquals(2, queries.size());
            nested.executeWithoutResult(inner -> provider.duringAuthorization(() -> {
                provider.findBySubjectId(subject).orElseThrow();
                assertEquals(4, queries.size(), "Inner transaction must query its own facts");
                return true;
            }));
            provider.findBySubjectId(subject).orElseThrow();
            assertEquals(4, queries.size(), "Outer authorization resumes its own unchanged facts");
            return true;
        }));
        tx.executeWithoutResult(status -> provider.duringAuthorization(() -> provider.findBySubjectId(subject)));
        assertEquals(6, queries.size(), "A new transaction must read again");
    }

    @Test
    void failedAuthorizationClearsFactsBeforeAnotherCheckAndRollback() {
        tx.executeWithoutResult(status -> {
            assertThrows(IllegalArgumentException.class, () -> provider.duringAuthorization(() -> {
                provider.actorCurrent(login);
                throw new IllegalArgumentException("abort authorization");
            }));
            jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
            assertThrows(RuntimeException.class, () -> provider.duringAuthorization(() -> provider.actorCurrent(login)));
            status.setRollbackOnly();
        });
        assertEquals(subject, provider.actor(login).principal().subjectId());
    }

    @Test
    void scopeRejectsMissingReadOnlyAndForeignDatasourceTransactions() {
        assertThrows(IllegalStateException.class, () -> provider.duringAuthorization(() -> true));
        var readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> assertThrows(IllegalStateException.class, () -> provider.duringAuthorization(() -> true)));
        var foreignSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        new TransactionTemplate(new DataSourceTransactionManager(foreignSource)).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> provider.duringAuthorization(() -> true)));
    }
}
