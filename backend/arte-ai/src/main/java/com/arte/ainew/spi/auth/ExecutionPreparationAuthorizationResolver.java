package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.config.NewAiProperties;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

/**
 * 在一次准备边界内解析当前写入资格和控制配置。
 * <p>
 * 实现为请求的身份和权限返回已授权的一致配置。
 * 结果仅属于本次操作，后续受理或发送边界必须重新解析。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 16:55 ✾
 */
public interface ExecutionPreparationAuthorizationResolver extends ExecutionAuthorizationResolver {

    /**
     * 在同一准备边界取得的授权和控制配置。
     *
     * @param authorization 已验证的身份、租户、工作空间和权限上限
     * @param configuration 包含已授权主体 Grant 的一致控制配置
     * @param catalogRevision 不可变目录的发布版本；为空表示不支持版本复核优化
     */
    record Preparation(ExecutionAuthorization authorization, NewAiProperties configuration, Long catalogRevision) {
        /**
         * 为不提供发布版本凭证的来源创建兼容准备结果。
         */
        public Preparation(ExecutionAuthorization authorization, NewAiProperties configuration) {
            this(authorization, configuration, null);
        }
        /**
         * 创建包含授权和配置两个必需部分的准备结果。
         *
         * @throws NullPointerException 授权或配置为空时抛出
         */
        public Preparation {
            Objects.requireNonNull(authorization);
            Objects.requireNonNull(configuration);
            if (catalogRevision != null && catalogRevision < 1)
                throw new IllegalArgumentException("Invalid catalog revision");
        }
    }

    /**
     * 根据可信认证名称，一次解析当前资格和配置。
     * 每次新订阅必须重新检查，不能复用此前的允许决定。
     *
     * @param name        可信入口捕获的已认证名称
     * @param tenantId    请求的租户标识
     * @param workspaceId 租户内请求的工作空间标识
     * @param scopes      请求的操作权限集合，不得超过当前授权范围
     * @return 已验证的准备结果，或授权、配置错误
     */
    Mono<Preparation> prepare(String name, String tenantId, String workspaceId, Set<String> scopes);
}
