package com.arte.core.pojo.workspace;

import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 内部收尾资格检查请求。目标须由登记工作项解析，不从普通 HTTP 请求直接生成。
 *
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
