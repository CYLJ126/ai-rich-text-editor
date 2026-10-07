package com.arte.ainew.context;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 可信入口提供的执行选项；不包含客户端可自报的主体身份。
 * workspace 和 scopes 是待验证的请求范围，budgetRef／releaseRef 等引用仍须对应服务校验。
 * 超时应由入口按平台上限约束；幂等键的作用域与请求摘要由受理服务验证。
 *
 * @param tenantId       本次操作选择的租户 ID，必填且非空白。
 *                       授权解析器结合真实登录身份校验租户范围，传入此值本身不授予访问权限。
 * @param workspaceId    租户内本次操作选择的工作空间 ID，必填且非空白。
 *                       与 tenantId、授权解析得到的主体 ID 共同确定会话、调用及预算等记录的归属。
 * @param scopes         可信入口为本次操作申请的权限范围，如 ai:conversation、ai:invoke、ai:read。
 *                       集合及元素不可为 null，元素不可为空白；构造时复制为不可变集合，允许空集合。
 *                       由授权解析器决定是否授予，不能直接采用客户端自报的权限。
 * @param timeout        本次上下文的相对有效时长，必填且必须大于零，由可信入口按平台最大时长约束。
 *                       工厂以当前时间加此时长分配绝对 deadline，授权解析也占用该期限。
 * @param traceId        可选的链路追踪 ID，用于关联日志和执行记录，不作为执行身份或幂等键。
 *                       为 null 时由工厂生成去掉连字符的 UUID；非 null 值须符合上下文的非空白约束。
 * @param budgetRef      可选的预算账户引用；业务服务校验账户存在性、归属及当前主体的使用资格。
 *                       无需预算的操作可传 null；指定引用不会初始化账户、预留余额或授予预算管理权限。
 * @param releaseRef     可选的固定发布标识，通常由服务端配置提供，用于约束调用使用的发布配置。
 *                       需要发布约束的业务入口会进一步校验，工厂仅将引用传入上下文。
 * @param idempotencyKey 可选的业务操作幂等键；创建会话、提交调用等受理入口要求提供，普通读取可传 null。
 *                       同一次操作的重试沿用原键，新操作使用新键；作用域、请求摘要及冲突由受理服务仲裁。
 *                       HTTP 创建会话入口从 Idempotency-Key 请求头读取，工厂不生成或持久化幂等记录。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionContextRequest(
        String tenantId,
        String workspaceId,
        Set<String> scopes,
        Duration timeout,
        String traceId,
        String budgetRef,
        String releaseRef,
        String idempotencyKey) {

    public ExecutionContextRequest {
        if (tenantId == null || tenantId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("Tenant and workspace must not be blank");
        }
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (scopes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }
}
