package com.arte.app.platform.identity;

import com.arte.core.pojo.platform.PrincipalIdentity;
import com.arte.core.spi.platform.PrincipalIdentityStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 唯一约束保存不可变身份关联；额外保存账户创建时间以拒绝被复用的账户主键。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
public final class JdbcPrincipalIdentityStore implements PrincipalIdentityStore {

    public static final String PROVIDER = "arte-rbac";
    private final JdbcTemplate jdbc;
    private final RbacAccountReader accounts;

    public JdbcPrincipalIdentityStore(DataSource dataSource, RbacAccountReader accounts) {
        jdbc = new JdbcTemplate(dataSource);
        this.accounts = accounts;
    }

    @Override
    public Optional<PrincipalIdentity> findBySubjectId(String subjectId) {
        return findAccountBinding(subjectId, false).map(AccountBinding::identity);
    }

    @Override
    public Optional<PrincipalIdentity> findByExternalIdentity(String provider, String externalUserId) {
        return find("provider = ? AND external_user_id = ?", provider, externalUserId).map(AccountBinding::identity);
    }

    /**
     * 调用方已取得主体门控；锁定读取最新映射，避免 REPEATABLE READ 的旧快照。
     */
    public Optional<PrincipalIdentity> findCurrentBySubjectId(String subjectId) {
        accounts.requireTransaction();
        return findAccountBinding(subjectId, true).map(AccountBinding::identity);
    }

    /**
     * Birth fingerprint is read with the identity row, without a second snapshot query.
     */
    public record AccountBinding(PrincipalIdentity identity, LocalDateTime externalCreatedAt) {
    }

    public Optional<AccountBinding> findAccountBinding(String subjectId, boolean currentRead) {
        if (currentRead) accounts.requireTransaction();
        return read(new ReadKey(currentRead, true, subjectId),
                () -> find("subject_id = ?" + (currentRead ? " FOR UPDATE" : ""), subjectId));
    }

    public Optional<AccountBinding> findAccountBindingByExternalId(String externalUserId) {
        return findAccountBindingByExternalId(externalUserId, false);
    }

    /**
     * 提交时的会话映射复核也必须使用当前读，不能沿用事务的普通快照。
     */
    public Optional<AccountBinding> findAccountBindingByExternalId(String externalUserId, boolean currentRead) {
        if (currentRead) accounts.requireTransaction();
        return read(new ReadKey(currentRead, false, externalUserId),
                () -> find("provider = ? AND external_user_id = ?" + (currentRead ? " FOR UPDATE" : ""), PROVIDER, externalUserId));
    }

    private record ReadKey(boolean current, boolean subject, String id) {
    }

    private Optional<AccountBinding> read(ReadKey key, Supplier<Optional<AccountBinding>> load) {
        var result = RbacAuthorizationReads.read(jdbc.getDataSource(), this, key, load);
        result.filter(value -> PROVIDER.equals(value.identity().provider())).ifPresent(value -> {
            RbacAuthorizationReads.remember(jdbc.getDataSource(), this, new ReadKey(key.current(), true, value.identity().subjectId()), result);
            RbacAuthorizationReads.remember(jdbc.getDataSource(), this, new ReadKey(key.current(), false, value.identity().externalUserId()), result);
        });
        return result;
    }

    public boolean matchesAccount(PrincipalIdentity identity, RbacAccountReader.Account account) {
        return jdbc.queryForObject("SELECT external_created_at FROM arte_platform_principal_identity WHERE subject_id = ?",
                (rs, n) -> rs.getObject(1, LocalDateTime.class), identity.subjectId()).equals(account.createdAt());
    }

    @Override
    public boolean insertIfAbsent(PrincipalIdentity identity) {
        accounts.requireTransaction();
        if (!PROVIDER.equals(identity.provider())) throw new IllegalArgumentException("Unsupported identity provider");
        var account = accounts.findCurrentById(Long.parseLong(identity.externalUserId())).orElseThrow();
        try {
            return jdbc.update("INSERT INTO arte_platform_principal_identity "
                            + "(subject_id, provider, external_user_id, external_created_at, created_at, retired_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", identity.subjectId(), identity.provider(), identity.externalUserId(),
                    account.createdAt(), Timestamp.from(identity.createdAt()),
                    identity.retiredAt() == null ? null : Timestamp.from(identity.retiredAt()), identity.version()) == 1;
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    @Override
    public boolean retire(String subjectId, Instant retiredAt, long expectedVersion) {
        accounts.requireTransaction();
        if (retiredAt == null || expectedVersion < 0) throw new IllegalArgumentException("Invalid retirement");
        return jdbc.update("UPDATE arte_platform_principal_identity SET retired_at = ?, version = version + 1 "
                        + "WHERE subject_id = ? AND version = ? AND retired_at IS NULL AND created_at <= ?",
                Timestamp.from(retiredAt), subjectId, expectedVersion, Timestamp.from(retiredAt)) == 1;
    }

    private Optional<AccountBinding> find(String predicate, Object... values) {
        var rows = jdbc.query("SELECT subject_id, provider, external_user_id, external_created_at, created_at, retired_at, version "
                        + "FROM arte_platform_principal_identity WHERE " + predicate,
                (rs, n) -> new AccountBinding(new PrincipalIdentity(rs.getString("subject_id"), rs.getString("provider"),
                        rs.getString("external_user_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toInstant(),
                        rs.getLong("version")), rs.getObject("external_created_at", LocalDateTime.class)), values);
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous principal identity");
        return rows.stream().findFirst();
    }
}
