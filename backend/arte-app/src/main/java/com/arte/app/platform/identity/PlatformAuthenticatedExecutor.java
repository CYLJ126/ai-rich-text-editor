package com.arte.app.platform.identity;

import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.TrustedActor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 公共 HTTP 的受信身份与数据库线程边界
 * <p>
 * 调用时仅复制账号 ID、名称及生命周期时间；订阅后重新核验当前账号，整次同步服务调用不切线程。
 * 不接受请求体提供的主体，不缓存授权，不自动重试；取消不能保证撤销已开始的 JDBC 提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
public final class PlatformAuthenticatedExecutor {

    private final RbacIdentityProvider identities;
    private final PlatformPersistenceExecutor executor;

    public PlatformAuthenticatedExecutor(RbacIdentityProvider identities, PlatformPersistenceExecutor executor) {
        this.identities = identities;
        this.executor = executor;
    }

    /**
     * 普通调用入口：复核身份并调度业务执行，不额外包裹事务。
     * 应用服务自行管理事务；此处只完成可信身份核验和整次数据库调用调度。
     */
    public <T> Mono<T> call(Authentication authentication, Function<TrustedActor, T> work) {
        return schedule(authentication, work, false);
    }

    /**
     * 事务调用入口：将身份复核和业务回调整体放入同一本地事务，没有事务时创建，有则加入。
     * 在同一数据库线程加入实际本地事务，回调须同步完成，不能等待网络。
     */
    public <T> Mono<T> inTransaction(Authentication authentication, Function<TrustedActor, T> work) {
        return schedule(authentication, work, true);
    }

    /**
     * 统一捕获会话身份，并将身份复核及完整业务调用调度到数据库工作线程。
     * 共用执行流程：立即捕获身份副本；订阅后在数据库线程重新核验身份、执行回调，并将调度拒绝转换为授权权威不可用错误。
     * <p>
     * 调用本方法时先取得最小身份副本，捕获失败转换为 Mono 错误；真正的账号／稳定主体核验和 work 执行延迟到订阅后进行，不把请求线程中的在线用户对象直接交给工作线程。
     * transaction 为 true 时将整次调用放入本地 required 事务，否则由应用服务自行管理事务。
     * 调度拒绝转换为 AUTHORITY_UNAVAILABLE，其余错误沿原响应式链传播，不自动重试。
     *
     * @param authentication 由宿主认证边界提供的本次请求认证信息
     * @param work           接收重新核验的 TrustedActor 并同步完成的业务回调
     * @param transaction    是否为身份复核和业务回调创建或加入本地事务
     * @param <T>            同步业务回调的结果类型
     * @return 订阅后在数据库工作线程执行的结果流；回调返回 null 时正常完成但不发出结果值
     */
    private <T> Mono<T> schedule(Authentication authentication, Function<TrustedActor, T> work, boolean transaction) {
        final Authentication snapshot;
        try {
            // 捕获 Spring Security 的独立认证身份副本
            snapshot = capture(authentication);
        } catch (PlatformException rejected) {
            return Mono.error(rejected);
        }
        // 业务回调，使用受信操作者执行
        Supplier<T> invocation = () -> work.apply(identities.actor(snapshot));
        // 调度拒绝转换
        Function<RejectedExecutionException, Throwable> rejectFunc = rejected ->
                new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, UUID.randomUUID().toString());
        return (transaction ? executor.inTransaction(invocation) : executor.read(invocation))
                .onErrorMap(RejectedExecutionException.class, rejectFunc);
    }

    /**
     * 校验请求认证信息的基本结构，并复制后续身份复核所需的最小会话字段。
     * <p>
     * 要求认证已标记成功、主体为包含 UserOnlineInfo 的 JwtUserDto、账号 ID 和创建时间存在，
     * 且认证名称与在线用户名称一致。仅复制账号 ID、名称及创建时间，重新包装为无凭据、无权限列表的认证副本，
     * 避免继续引用原在线用户对象或携带旧权限。这里不重新验证 JWT，也不查询当前账号状态或授予资源权限；
     * 当前账号及稳定主体仍由订阅后的 identities.actor 重新核验。
     *
     * @param authentication 可信认证边界提供的请求认证信息，不能由业务请求体构造
     * @return 仅携带最小会话身份字段、供后续核验使用的独立认证副本
     * @throws PlatformException 认证缺失、主体类型不符、必要身份字段缺失或名称不一致
     */
    private static Authentication capture(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserDto(UserOnlineInfo online))
                || online.getId() == null || online.getAccountCreatedAt() == null
                || !Objects.equals(authentication.getName(), online.getUserName())) {
            throw new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, UUID.randomUUID().toString());
        }
        UserOnlineInfo copy = new UserOnlineInfo().setId(online.getId()).setUserName(online.getUserName())
                .setAccountCreatedAt(online.getAccountCreatedAt());
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(copy), null, List.of());
    }
}
