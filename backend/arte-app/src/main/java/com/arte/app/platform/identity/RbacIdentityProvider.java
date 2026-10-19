package com.arte.app.platform.identity;

import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.PrincipalIdentity;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.platform.IdentityProvider;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 登录账户是状态权威，公共映射是主体标识权威；固定 AI 授权不在此改写。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
public final class RbacIdentityProvider implements IdentityProvider {

    private final RbacAccountReader accounts;
    private final JdbcPrincipalIdentityStore identities;
    private final TransactionTemplate transactions;
    private final PlatformIdentityProperties properties;
    private final Supplier<List<NewAiProperties.Grant>> legacyGrants;
    private final Clock clock;

    public RbacIdentityProvider(RbacAccountReader accounts, JdbcPrincipalIdentityStore identities,
                                TransactionTemplate transactions, PlatformIdentityProperties properties,
                                Supplier<List<NewAiProperties.Grant>> legacyGrants, Clock clock) {
        this.accounts = accounts;
        this.identities = identities;
        this.transactions = transactions;
        this.properties = properties;
        this.legacyGrants = legacyGrants;
        this.clock = clock;
    }

    @Override
    public Optional<Principal> resolveAuthenticatedName(String authenticatedName) {
        return accounts.findByName(authenticatedName).map(this::resolve);
    }

    /**
     * HTTP 适配入口：必须同时核验认证会话的账户主键与名称，不能仅接受一个名称字符串。
     */
    public TrustedActor actor(Authentication authentication) {
        return actor(authentication, false);
    }

    /**
     * 提交事务中的会话复核：主键、名称和创建时间均用锁定当前读，拒绝旧 MVCC 会话证据。
     */
    public TrustedActor actorCurrent(Authentication authentication) {
        return actor(authentication, true);
    }

    /**
     * 本次可写事务的同步授权检查内复用原始账户、身份映射及 RBAC 事实。
     * 回调结束即清除，不跨入口、提交或 Worker 边界，也不得包住业务写入。
     * 普通读取与锁定当前读取分别保存，所有生命周期和会话证据仍逐次校验。
     */
    public <T> T duringAuthorization(Supplier<T> work) {
        return accounts.duringAuthorization(work);
    }

    private TrustedActor actor(Authentication authentication, boolean current) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserDto(UserOnlineInfo userOnlineInfo))) {
            throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        }
        var account = (current && userOnlineInfo.getId() != null ? accounts.findCurrentById(userOnlineInfo.getId()) : accounts.findById(userOnlineInfo.getId()))
                .filter(a -> a.active() && a.name().equals(authentication.getName())
                        && a.createdAt().equals(userOnlineInfo.getAccountCreatedAt()))
                .orElseThrow(() -> failure(PlatformErrorCodeEnum.UNAUTHENTICATED));
        var principal = resolve(account, current);
        if (principal.status() != PrincipalStatusEnum.ACTIVE) throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        return new TrustedActor(principal, clock.instant());
    }

    @Override
    public Optional<Principal> findBySubjectId(String subjectId) {
        return identities.findAccountBinding(subjectId, false).map(identity -> principal(identity, false));
    }

    @Override
    public Optional<Principal> findCurrentBySubjectId(String subjectId) {
        accounts.requireTransaction();
        return identities.findAccountBinding(subjectId, true).map(identity -> principal(identity, true));
    }

    /**
     * Worker 的可信执行主体没有 HTTP 会话；锁定现有映射及账户，核对稳定主体、当前名称和账户生命周期。
     * 不根据任务里的名称创建身份，不重建 JWT，也不把旧执行快照当作当前账户资格。
     */
    public Optional<Principal> findCurrentExecutionPrincipal(ExecutionPrincipal expected) {
        accounts.requireTransaction();
        if (expected.kind() != ExecutionPrincipal.Kind.USER) return Optional.empty();
        return identities.findAccountBinding(expected.subjectId(), true).flatMap(binding -> {
            long userId;
            try {
                userId = Long.parseLong(binding.identity().externalUserId());
            } catch (NumberFormatException invalid) {
                throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
            }
            return accounts.findCurrentById(userId).filter(account -> account.name().equals(expected.subjectName())).map(account -> {
                validateBinding(binding, account);
                return principal(binding.identity(), account);
            });
        });
    }

    private Principal resolve(RbacAccountReader.Account account) {
        return resolve(account, false);
    }

    private Principal resolve(RbacAccountReader.Account account, boolean currentRead) {
        var existing = identities.findAccountBindingByExternalId(String.valueOf(account.id()), currentRead);
        if (existing.isPresent()) {
            validateBinding(existing.get(), account);
            return principal(existing.get().identity(), account);
        }
        if (!account.active()) throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        if (TransactionSynchronizationManager.isActualTransactionActive()) accounts.requireTransaction();
        return transactions.execute(status -> {
            var current = accounts.findCurrentById(account.id()).filter(a -> a.active() && a.name().equals(account.name())
                            && a.createdAt().equals(account.createdAt()))
                    .orElseThrow(() -> failure(PlatformErrorCodeEnum.UNAUTHENTICATED));
            var winner = identities.findAccountBindingByExternalId(String.valueOf(current.id()));
            if (winner.isPresent()) {
                validateBinding(winner.get(), current);
                return principal(winner.get().identity(), current);
            }
            String subjectId = candidate(current);
            var identity = new PrincipalIdentity(subjectId, JdbcPrincipalIdentityStore.PROVIDER,
                    String.valueOf(current.id()), clock.instant(), null, 0);
            if (!identities.insertIfAbsent(identity)) throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
            return new Principal(subjectId, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE);
        });
    }

    private String candidate(RbacAccountReader.Account account) {
        var binding = properties.bindings().stream().filter(b -> b.userId() == account.id()).findFirst();
        var named = legacyGrants.get().stream().filter(g -> g.subjectName().equals(account.name())).toList();
        if (binding.isPresent()) {
            if (named.stream().anyMatch(g -> g.principalKind() != ExecutionPrincipal.Kind.USER
                    || !g.subjectId().equals(binding.get().subjectId())))
                throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
            return binding.get().subjectId();
        }
        // 部署前既有用户必须经核对；即使当前 grants 已删除，也不自动切断其历史身份。
        if (account.id() <= properties.legacyUserIdCeiling() || !named.isEmpty())
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        return UUID.randomUUID().toString();
    }

    private void validateBinding(JdbcPrincipalIdentityStore.AccountBinding binding, RbacAccountReader.Account account) {
        var identity = binding.identity();
        if (!JdbcPrincipalIdentityStore.PROVIDER.equals(identity.provider())
                || !String.valueOf(account.id()).equals(identity.externalUserId())
                || !account.createdAt().equals(binding.externalCreatedAt()))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        var configured = properties.bindings().stream().filter(b -> b.userId() == account.id()).findFirst();
        if (configured.isPresent() && !configured.get().subjectId().equals(identity.subjectId()))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        if (legacyGrants.get().stream().anyMatch(g -> g.subjectName().equals(account.name())
                && (g.principalKind() != ExecutionPrincipal.Kind.USER || !g.subjectId().equals(identity.subjectId()))))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
    }

    private Principal principal(JdbcPrincipalIdentityStore.AccountBinding binding, boolean currentRead) {
        var identity = binding.identity();
        if (!JdbcPrincipalIdentityStore.PROVIDER.equals(identity.provider()))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        if (identity.retiredAt() != null)
            return new Principal(identity.subjectId(), PrincipalKindEnum.USER, PrincipalStatusEnum.DELETED);
        long userId;
        try {
            userId = Long.parseLong(identity.externalUserId());
        } catch (NumberFormatException invalid) {
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        }
        var account = currentRead ? accounts.findCurrentById(userId) : accounts.findById(userId);
        if (account.isEmpty())
            return new Principal(identity.subjectId(), PrincipalKindEnum.USER, PrincipalStatusEnum.DELETED);
        validateBinding(binding, account.get());
        return principal(identity, account.get());
    }

    private Principal principal(PrincipalIdentity identity, RbacAccountReader.Account account) {
        return new Principal(identity.subjectId(), PrincipalKindEnum.USER, identity.retiredAt() != null
                ? PrincipalStatusEnum.DELETED : account.active() ? PrincipalStatusEnum.ACTIVE : PrincipalStatusEnum.SUSPENDED);
    }

    private static PlatformException failure(PlatformErrorCodeEnum code) {
        return new PlatformException(code, UUID.randomUUID().toString());
    }
}
