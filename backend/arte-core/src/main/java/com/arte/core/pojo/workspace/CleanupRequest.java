package com.arte.core.pojo.workspace;

import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 内部收尾资格检查请求。目标须由登记工作项解析，不从普通 HTTP 请求直接生成。
 *
 * @param authorizationId              待检查的收尾授权 ID
 * @param operationId                  对应的关闭操作 ID
 * @param scope                        目标空间范围，包含租户 ID 和空间 ID
 * @param domain                       负责处理该工作项的业务领域标识
 * @param workKey                      关闭操作中该领域下的工作项标识
 * @param targetRef                    从已登记工作项解析出的清理目标引用
 * @param action                       待校验的清理动作
 * @param expectedAuthorizationVersion 检查时须匹配的收尾授权版本
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CleanupRequest(
        String authorizationId,
        String operationId,
        Scope scope,
        String domain,
        String workKey,
        String targetRef,
        Action action,
        long expectedAuthorizationVersion) {

    public CleanupRequest {
        PlatformChecks.id(authorizationId, "authorizationId");
        PlatformChecks.id(operationId, "operationId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.token(domain, "domain");
        PlatformChecks.id(workKey, "workKey");
        PlatformChecks.id(targetRef, "targetRef");
        Objects.requireNonNull(action, "action");
        PlatformChecks.version(expectedAuthorizationVersion, "expectedAuthorizationVersion");
    }
}
