package com.arte.app.platform.identity;

import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.enums.StatusEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真正 MyBatis/JDBC、本地事务与数据库唯一约束；不依赖 Redis 或运行中的 app。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
class RbacIdentityProviderTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RbacAccountReader accounts;
    private JdbcPrincipalIdentityStore store;
    private RbacIdentityProvider provider;
    private List<NewAiProperties.Grant> grants = List.of();

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE arte_rbac_user(id BIGINT PRIMARY KEY, user_name VARCHAR(64) UNIQUE, status INT, create_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE arte_platform_principal_identity(subject_id VARCHAR(256) PRIMARY KEY, provider VARCHAR(96), "
                + "external_user_id VARCHAR(64), external_created_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL, retired_at TIMESTAMP, "
                + "version BIGINT NOT NULL, UNIQUE(provider,external_user_id))");
        accounts = new RbacAccountReader(source, new RbacAccountMapperConfiguration().identityUserMapper(source));
        store = new JdbcPrincipalIdentityStore(source, accounts);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        provider = create(0, List.of());
        add(1, "alice");
    }

    private RbacIdentityProvider create(long ceiling, List<PlatformIdentityProperties.Binding> bindings) {
        return new RbacIdentityProvider(accounts, store, tx, new PlatformIdentityProperties(true, ceiling, bindings), () -> grants, Clock.systemUTC());
    }

    private void add(long id, String name) {
        jdbc.update("INSERT INTO arte_rbac_user VALUES(?,?,1,?)", id, name, Timestamp.valueOf("2026-10-10 10:00:00"));
    }

    private String subject() {
        return provider.resolveAuthenticatedName("alice").orElseThrow().subjectId();
    }

    @Test
    void fullLongAccountIdPreservesReviewedSubjectAndCurrentRead() {
        long accountId = Long.MAX_VALUE;
        add(accountId, "large-account");
        provider = create(accountId, List.of(new PlatformIdentityProperties.Binding(accountId, "reviewed-large-subject")));
        assertEquals("reviewed-large-subject", provider.resolveAuthenticatedName("large-account").orElseThrow().subjectId());
        assertEquals(Long.toString(accountId), store.findBySubjectId("reviewed-large-subject").orElseThrow().externalUserId());
        var session = new JwtUserDto(new UserOnlineInfo().setId(accountId).setUserName("large-account")
                .setStatus(StatusEnum.DOING).setAccountCreatedAt(accounts.findById(accountId).orElseThrow().createdAt()));
        assertEquals("reviewed-large-subject", provider.actor(new UsernamePasswordAuthenticationToken(session, null, List.of()))
                .principal().subjectId());
        tx.executeWithoutResult(status -> assertEquals(PrincipalStatusEnum.ACTIVE,
                provider.findCurrentBySubjectId("reviewed-large-subject").orElseThrow().status()));
        jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=?", accountId);
        assertEquals(PrincipalStatusEnum.SUSPENDED, provider.findBySubjectId("reviewed-large-subject").orElseThrow().status());
    }

    @Test
    void allocatesOpaqueSubjectOnceAndRenamePreservesIt() {
        String id = subject();
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals(id, subject());
        jdbc.update("UPDATE arte_rbac_user SET user_name='renamed' WHERE id=1");
        assertTrue(provider.resolveAuthenticatedName("alice").isEmpty());
        assertEquals(id, provider.resolveAuthenticatedName("renamed").orElseThrow().subjectId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_principal_identity", Integer.class));
    }

    @Test
    void concurrentInitializationHasOneSubject() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 16).mapToObj(i -> pool.submit(this::subject)).toList();
            String first = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_principal_identity", Integer.class));
    }

    @Test
    void outerRollbackAlsoRollsBackIdentityMapping() {
        tx.executeWithoutResult(status -> {
            subject();
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_principal_identity", Integer.class));
    }

    @Test
    void disabledAccountIsNotActiveEvenWithExistingMapping() {
        String id = subject();
        jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        assertEquals(PrincipalStatusEnum.SUSPENDED, provider.findBySubjectId(id).orElseThrow().status());
        assertEquals(PrincipalStatusEnum.SUSPENDED, provider.resolveAuthenticatedName("alice").orElseThrow().status());
    }

    @Test
    void inactiveUnmappedAccountCannotCreateIdentity() {
        jdbc.update("UPDATE arte_rbac_user SET status=0 WHERE id=1");
        assertEquals(PlatformErrorCodeEnum.UNAUTHENTICATED, assertThrows(PlatformException.class, this::subject).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_principal_identity", Integer.class));
    }

    @Test
    void currentIdentityRequiresSameDatasourceTransactionAndSeesDatabaseState() {
        String id = subject();
        assertThrows(IllegalStateException.class, () -> provider.findCurrentBySubjectId(id));
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
            assertEquals(PrincipalStatusEnum.SUSPENDED, provider.findCurrentBySubjectId(id).orElseThrow().status());
        });
        var other = new DriverManagerDataSource("jdbc:h2:mem:otherIdentity", "sa", "");
        new TransactionTemplate(new DataSourceTransactionManager(other)).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> provider.findCurrentBySubjectId(id)));
    }

    @Test
    void retiredMappingRemainsDeletedAfterReenableAndCannotBeRebound() {
        String id = subject();
        tx.executeWithoutResult(status -> assertTrue(store.retire(id, Instant.now().plusSeconds(1), 0)));
        assertEquals(PrincipalStatusEnum.DELETED, provider.findBySubjectId(id).orElseThrow().status());
        assertEquals(PrincipalStatusEnum.DELETED, provider.resolveAuthenticatedName("alice").orElseThrow().status());
        tx.executeWithoutResult(status -> assertFalse(store.retire(id, Instant.now().plusSeconds(2), 0)));
    }

    @Test
    void sameNameNewAccountDoesNotInheritOldIdentity() {
        String old = subject();
        jdbc.update("DELETE FROM arte_rbac_user WHERE id=1");
        add(2, "alice");
        assertNotEquals(old, subject());
        assertEquals(PrincipalStatusEnum.DELETED, provider.findBySubjectId(old).orElseThrow().status());
    }

    @Test
    void reusedPrimaryKeyIsRejectedByBirthFingerprint() {
        subject();
        jdbc.update("UPDATE arte_rbac_user SET create_time='2026-10-11 00:00:00' WHERE id=1");
        assertEquals(PlatformErrorCodeEnum.IDENTITY_CONFLICT, assertThrows(PlatformException.class, this::subject).getCode());
    }

    @Test
    void reviewedBindingPreservesOriginalAiSubjectAndScope() {
        grants = List.of(grant("alice", "old-ai-subject"));
        provider = create(1, List.of(new PlatformIdentityProperties.Binding(1, "old-ai-subject")));
        assertEquals("old-ai-subject", subject());
        assertEquals("old-ai-subject", grants.getFirst().subjectId());
        assertEquals("old-tenant", grants.getFirst().tenantId());
        assertEquals("old-space", grants.getFirst().workspaceId());
    }

    @Test
    void preexistingAccountNeedsReviewedBindingEvenIfGrantWasRemoved() {
        provider = create(1, List.of());
        assertEquals(PlatformErrorCodeEnum.IDENTITY_CONFLICT, assertThrows(PlatformException.class, this::subject).getCode());
    }

    @Test
    void namedAiGrantCannotBeAutomaticallyBoundToAnAccount() {
        grants = List.of(grant("alice", "old-ai-subject"));
        assertEquals(PlatformErrorCodeEnum.IDENTITY_CONFLICT, assertThrows(PlatformException.class, this::subject).getCode());
    }

    @Test
    void conflictingGrantsOrPersistedBindingsDoNotOverwriteMapping() {
        String original = subject();
        provider = create(1, List.of(new PlatformIdentityProperties.Binding(1, "different")));
        assertEquals(PlatformErrorCodeEnum.IDENTITY_CONFLICT, assertThrows(PlatformException.class, this::subject).getCode());
        provider = create(0, List.of());
        grants = List.of(grant("alice", "different"));
        assertThrows(PlatformException.class, this::subject);
        assertEquals(original, store.findByExternalIdentity("arte-rbac", "1").orElseThrow().subjectId());
    }

    @Test
    void subjectUniqueConstraintCannotTransferAnotherAccountsMapping() {
        provider = create(1, List.of(new PlatformIdentityProperties.Binding(1, "original")));
        subject();
        add(2, "bob");
        provider = create(2, List.of(new PlatformIdentityProperties.Binding(2, "original")));
        assertEquals(PlatformErrorCodeEnum.IDENTITY_CONFLICT,
                assertThrows(PlatformException.class, () -> provider.resolveAuthenticatedName("bob")).getCode());
        assertEquals("1", store.findBySubjectId("original").orElseThrow().externalUserId());
    }

    @Test
    void httpActorRequiresAuthenticatedSessionPrimaryKeyAndName() {
        var session = new JwtUserDto(new UserOnlineInfo().setId(1L).setUserName("alice").setStatus(StatusEnum.DOING)
                .setAccountCreatedAt(accounts.findById(1L).orElseThrow().createdAt()));
        var auth = new UsernamePasswordAuthenticationToken(session, null, List.of());
        assertEquals(subject(), provider.actor(auth).principal().subjectId());
        assertThrows(PlatformException.class, () -> provider.actor(new UsernamePasswordAuthenticationToken("alice", null, List.of())));
        jdbc.update("UPDATE arte_rbac_user SET user_name='renamed' WHERE id=1");
        add(2, "alice");
        assertEquals(PlatformErrorCodeEnum.UNAUTHENTICATED, assertThrows(PlatformException.class, () -> provider.actor(auth)).getCode());
    }

    @Test
    void configurationRejectsUnreviewedCutoffAndDuplicateBindings() {
        assertThrows(IllegalArgumentException.class, () -> new PlatformIdentityProperties(true, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> create(2, List.of(new PlatformIdentityProperties.Binding(1, "same"),
                new PlatformIdentityProperties.Binding(2, "same"))));
    }

    private NewAiProperties.Grant grant(String name, String subject) {
        return new NewAiProperties.Grant(name, subject, ExecutionPrincipal.Kind.USER, "old-tenant", "old-space", "grant", true,
                Set.of("chat"), Set.of(), Set.of());
    }

    @Test
    void fixedAiResolverPreservesOwnerAndRejectsDisabledAccount() {
        grants = List.of(grant("alice", "old-ai-subject"));
        provider = create(1, List.of(new PlatformIdentityProperties.Binding(1, "old-ai-subject")));
        var ai = new NewAiProperties(false, null, null, null, null, grants, null, null, null, null, null);
        var resolver = new RbacFixedExecutionAuthorizationResolver(provider, ai, Schedulers.boundedElastic());
        var result = resolver.resolve("alice", "old-tenant", "old-space", Set.of("chat")).block();
        assertNotNull(result);
        assertEquals("old-ai-subject", result.principal().subjectId());
        assertEquals("old-tenant", result.tenantId());
        assertEquals("old-space", result.workspaceId());
        assertEquals("grant", result.grantRef());
        jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
        assertThrows(AccessDeniedException.class, () -> resolver.resolve("alice", "old-tenant", "old-space", Set.of("chat")).block());
    }

    @Test
    void newSameNamedAccountCannotInheritFixedAiGrant() {
        grants = List.of(grant("alice", "old-ai-subject"));
        provider = create(1, List.of(new PlatformIdentityProperties.Binding(1, "old-ai-subject")));
        subject();
        jdbc.update("UPDATE arte_rbac_user SET user_name='renamed' WHERE id=1");
        add(2, "alice");
        var ai = new NewAiProperties(false, null, null, null, null, grants, null, null, null, null, null);
        var resolver = new RbacFixedExecutionAuthorizationResolver(provider, ai, Schedulers.boundedElastic());
        assertThrows(AccessDeniedException.class, () -> resolver.resolve("alice", "old-tenant", "old-space", Set.of("chat")).block());
    }
}
