package com.arte.core.api.access;

import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.VerifiedAccess;
import com.arte.core.pojo.platform.TrustedActor;

import java.util.List;

/**
 * 唯一通用动作判断入口
 * <p>
 * 主体、成员、政策禁止、资源事实、允许动作、领域条件依次检查；默认拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface AuthorizationService {

    /**
     * 检查指定动作的当前访问权限。
     * 权限不足时，返回拒绝结果，由调用方处理。用于判断按钮是否可用、筛选可见资源。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param request 包含范围、资源引用、动作与用途的权限检查请求
     * @return 包含允许或拒绝、原因及权限版本的判断结果
     */
    AccessDecision check(TrustedActor actor, AccessRequest request);

    /**
     * 要求指定动作的当前访问权限，检查未通过时拒绝调用。
     * 权限不足时，抛出 PlatformException，阻止继续执行。用于业务操作前强制校验权限。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param request 包含范围、资源引用、动作与用途的权限检查请求
     * @return 本次验证通过的访问快照，最终写入仍需事务内重查
     * @throws com.arte.core.exception.platform.PlatformException 权限检查未通过
     */
    VerifiedAccess require(TrustedActor actor, AccessRequest request);

    /**
     * 批量检查访问权限。最多 100 项，顺序与输入一致，单项与批量采用同一规则。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param requests 权限检查请求列表，最多 100 项
     * @return 与输入顺序一致的逐项权限判断结果
     */
    List<AccessDecision> batchCheck(TrustedActor actor, List<AccessRequest> requests);
}
