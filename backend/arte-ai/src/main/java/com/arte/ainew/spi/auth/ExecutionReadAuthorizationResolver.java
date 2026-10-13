package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.config.NewAiProperties;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

/**
 * 可信只读入口；每次订阅读取新的资格快照，配置只按需加载，不持久化或跨请求缓存。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 22:05 ✾
 */
public interface ExecutionReadAuthorizationResolver extends ExecutionAuthorizationResolver {
    record Snapshot(ExecutionAuthorization authorization, NewAiProperties configuration) {
        public Snapshot {
            Objects.requireNonNull(authorization);
        }
    }

    Mono<Snapshot> readSnapshot(String name, String tenantId, String workspaceId,
                                Set<String> scopes, boolean includeConfiguration);
}
