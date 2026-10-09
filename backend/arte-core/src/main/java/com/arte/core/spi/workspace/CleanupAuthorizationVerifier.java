package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.CleanupRequest;
import com.arte.core.pojo.workspace.VerifiedCleanupAuthorization;

/**
 * 内部资格检查
 * <p>
 * 在实际收尾事务内，确认当前执行者是否可以执行指定操作。
 * 必须在实际领域收尾事务参与校验当前服务身份、工作项、范围、动作、目标及有效期；不开放普通 HTTP。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface CleanupAuthorizationVerifier {

    /**
     * 在实际收尾事务内校验服务身份及收尾授权，失败时拒绝执行。
     */
    VerifiedCleanupAuthorization requireInCurrentTransaction(TrustedActor actor, CleanupRequest request);
}
