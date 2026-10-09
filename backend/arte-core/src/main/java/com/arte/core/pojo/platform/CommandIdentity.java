package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 完整幂等身份
 * <p>
 * 摘要采用规范化结构编码，不拼接有歧义的字符串。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandIdentity(
        String actorSubjectId,
        CommandScope scope,
        String commandType,
        String operationKey) {

    public CommandIdentity {
        PlatformChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.token(commandType, "commandType");
        PlatformChecks.id(operationKey, "operationKey");
    }
}
