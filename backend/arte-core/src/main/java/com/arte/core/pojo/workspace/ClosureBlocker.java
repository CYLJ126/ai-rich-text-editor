package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 领域返回的最小关闭阻塞项；不含回复正文或供应商明文证据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureBlocker(
        String domain,
        String workKey,
        String code,
        boolean retryable) {
    public ClosureBlocker {
        PlatformChecks.token(domain, "domain");
        PlatformChecks.id(workKey, "workKey");
        PlatformChecks.id(code, "code");
    }
}
