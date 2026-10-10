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
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserDto(UserOnlineInfo userOnlineInfo))) {
            throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        }
        var account = accounts.findById(userOnlineInfo.getId())
                .filter(a -> a.active() && a.name().equals(authentication.getName())
                        && a.createdAt().equals(userOnlineInfo.getAccountCreatedAt()))
                .orElseThrow(() -> failure(PlatformErrorCodeEnum.UNAUTHENTICATED));
        var principal = resolve(account);
        if (principal.status() != PrincipalStatusEnum.ACTIVE) throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        return new TrustedActor(principal, clock.instant());
    }

    @Override
    public Optional<Principal> findBySubjectId(String subjectId) {
        return identities.findBySubjectId(subjectId).map(identity -> principal(identity, false));
    }

    @Override
    public Optional<Principal> findCurrentBySubjectId(String subjectId) {
        accounts.requireTransaction();
        return identities.findCurrentBySubjectId(subjectId).map(identity -> principal(identity, true));
    }

    private Principal resolve(RbacAccountReader.Account account) {
        var existing = identities.findByExternalIdentity(JdbcPrincipalIdentityStore.PROVIDER, String.valueOf(account.id()));
        if (existing.isPresent()) {
            validateBinding(existing.get(), account);
            return principal(existing.get(), false);
        }
        if (!account.active()) throw failure(PlatformErrorCodeEnum.UNAUTHENTICATED);
        if (TransactionSynchronizationManager.isActualTransactionActive()) accounts.requireTransaction();
        return transactions.execute(status -> {
            var current = accounts.findCurrentById(account.id()).filter(a -> a.active() && a.name().equals(account.name())
                            && a.createdAt().equals(account.createdAt()))
                    .orElseThrow(() -> failure(PlatformErrorCodeEnum.UNAUTHENTICATED));
            var winner = identities.findByExternalIdentity(JdbcPrincipalIdentityStore.PROVIDER, String.valueOf(current.id()));
            if (winner.isPresent()) {
                validateBinding(winner.get(), current);
                return principal(winner.get(), false);
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

    private void validateBinding(PrincipalIdentity identity, RbacAccountReader.Account account) {
        if (!JdbcPrincipalIdentityStore.PROVIDER.equals(identity.provider())
                || !String.valueOf(account.id()).equals(identity.externalUserId())
                || !identities.matchesAccount(identity, account))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        var configured = properties.bindings().stream().filter(b -> b.userId() == account.id()).findFirst();
        if (configured.isPresent() && !configured.get().subjectId().equals(identity.subjectId()))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
        if (legacyGrants.get().stream().anyMatch(g -> g.subjectName().equals(account.name())
                && (g.principalKind() != ExecutionPrincipal.Kind.USER || !g.subjectId().equals(identity.subjectId()))))
            throw failure(PlatformErrorCodeEnum.IDENTITY_CONFLICT);
    }

    private Principal principal(PrincipalIdentity identity, boolean currentRead) {
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
        validateBinding(identity, account.get());
        return new Principal(identity.subjectId(), PrincipalKindEnum.USER,
                account.get().active() ? PrincipalStatusEnum.ACTIVE : PrincipalStatusEnum.SUSPENDED);
    }

    private static PlatformException failure(PlatformErrorCodeEnum code) {
        return new PlatformException(code, UUID.randomUUID().toString());
    }
}
