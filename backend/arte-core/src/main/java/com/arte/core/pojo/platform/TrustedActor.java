package com.arte.core.pojo.platform;

import java.time.Instant;
import java.util.Objects;

/**
 * 受信操作者
 * <p>
 * 表示经可信身份适配层核验的本次操作者，作为公共服务的入参，用于身份重检、授权判断和审计。
 * 由可信登录／服务身份适配创建。严禁作为 HTTP 请求体绑定；构造对象不等于完成认证。
 * 作为公共应用服务的操作者参数，供当前身份重检、授权判断和审计使用；具体操作权限由授权服务结合范围、资源和动作校验。
 *
 * @param principal       可信身份适配层确认的主体快照，不能为空，包含稳定主体标识、主体类型及核验时的状态；后续操作仍须按规则读取当前身份状态。
 * @param authenticatedAt 可信身份适配层完成本次身份核验的时间，不能为空；当前 RBAC 适配在创建操作者时记录该时点，不表示原始登录时间或授权有效期。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TrustedActor(Principal principal, Instant authenticatedAt) {

    public TrustedActor {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
}
