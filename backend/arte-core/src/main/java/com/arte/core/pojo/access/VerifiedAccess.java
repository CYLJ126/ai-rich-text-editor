package com.arte.core.pojo.access;

import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;

import java.time.Instant;
import java.util.Objects;

/**
 * require 返回的已检查快照；不跳过最终写入／派发门控，不允许从 HTTP 反序列化后视为可信。
 *
 * @param principal 本次授权检查的可信操作者主体，包含稳定主体标识、主体类型及状态。
 * @param request   已通过授权检查的请求，包含授权范围、目标资源、单个动作及用途。
 * @param versions  本次授权判断所依据的租户、空间、成员关系、ACL 及资源版本快照，不适用的版本项为空。
 * @param checkedAt 本次授权检查的时间，不表示授权在此后持续有效。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VerifiedAccess(Principal principal, AccessRequest request, AuthorizationVersions versions,
                             Instant checkedAt) {

    public VerifiedAccess {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
