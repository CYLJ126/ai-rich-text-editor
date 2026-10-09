package com.arte.core.pojo.access;

import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;

import java.time.Instant;
import java.util.Objects;

/**
 * require 返回的已检查快照；不跳过最终写入／派发门控，不允许从 HTTP 反序列化后视为可信。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VerifiedAccess(
        Principal principal,
        AccessRequest request,
        AuthorizationVersions versions,
        Instant checkedAt) {
    public VerifiedAccess {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
