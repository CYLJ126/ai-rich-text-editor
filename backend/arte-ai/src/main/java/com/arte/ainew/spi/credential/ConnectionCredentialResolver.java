package com.arte.ainew.spi.credential;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Mono;

/**
 * 仅供 ConnectionRuntime 在发送边界调用；实际 Vault／KMS／数据库实现可替换默认环境变量解析器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public interface ConnectionCredentialResolver {

    Mono<BearerCredential> resolve(DefinitionRef secret, ExecutionContext context);

    /**
     * 实例内凭据，不实现 Serializable；禁止写入持久化对象、普通结果或日志。
     */
    record BearerCredential(String token) {
        public BearerCredential {
            if (token == null || token.isEmpty() || token.length() > 8192
                    || token.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw new IllegalArgumentException("Invalid bearer credential");
            }
        }

        @Override
        public @NonNull String toString() {
            return "BearerCredential[REDACTED]";
        }
    }
}
