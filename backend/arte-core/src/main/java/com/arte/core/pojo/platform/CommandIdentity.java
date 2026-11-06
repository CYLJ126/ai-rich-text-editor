package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 标识命令的幂等记录。请求摘要采用规范化结构编码。
 *
 * @param actorSubjectId 发起命令的主体 ID
 * @param scope          命令执行范围
 * @param commandType    命令类型标识
 * @param operationKey   调用方提供的幂等操作键
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
