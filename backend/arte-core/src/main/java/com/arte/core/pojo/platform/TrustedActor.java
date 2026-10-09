package com.arte.core.pojo.platform;

import java.time.Instant;
import java.util.Objects;

/**
 * 受信操作者
 * <p>
 * 由可信登录／服务身份适配创建。严禁作为 HTTP 请求体绑定；构造对象不等于完成认证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TrustedActor(Principal principal, Instant authenticatedAt) {

    public TrustedActor {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
}
