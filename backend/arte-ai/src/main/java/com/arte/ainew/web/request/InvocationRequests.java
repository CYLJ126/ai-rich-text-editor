package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 调用状态、结果、事件重放及 SSE 订阅的 HTTP 请求参数。
 * scope 仅选择租户与工作空间，须由服务端验证；身份、权限及读取期限由服务端解析。
 * invocationId 和事件游标不提供访问权限，读取不会重新执行模型调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public final class InvocationRequests {

    private InvocationRequests() {
    }

    /**
     * 查询指定调用的状态或结果。
     *
     * @param scope        租户与工作空间，必填并校验内部字段；服务端还需验证当前用户的访问权限
     * @param invocationId 平台调用 ID，必填且非空白，最多 256 个字符；不是会话 ID 或供应商请求 ID
     */
    public record Query(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String invocationId) {
    }

    /**
     * 从指定游标建立 SSE 订阅，先补读历史，再接收新提交事件的通知。
     * 游标低于历史裁剪边界时返回游标过期，需查询调用快照恢复；0 也可能过期。
     *
     * @param scope         租户与工作空间，必填并校验内部字段；服务端验证权限及调用归属
     * @param invocationId  要订阅的平台调用 ID，必填且非空白，最多 256 个字符
     * @param afterSequence 最后已处理的事件序号，必填且至少为 0；仅接收更大序号的事件，0 表示从头读取
     */
    public record Watch(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String invocationId,
                        @NotNull @Min(0) Long afterSequence) {
    }

    /**
     * 分页重放已提交事件，每次只读取一页，不建立长连接。
     * 后续请求使用响应中的 nextCursor；游标低于历史裁剪边界时返回游标过期，不静默跳过历史。
     *
     * @param scope         租户与工作空间，必填并校验内部字段；服务端验证权限及调用归属
     * @param invocationId  要重放的平台调用 ID，必填且非空白，最多 256 个字符
     * @param afterSequence 排他读取位置，必填且至少为 0；仅读取更大序号的事件，0 表示从头读取但仍可能过期
     * @param limit         单页最多返回的事件数，必填，范围为 1～256
     */
    public record Replay(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String invocationId,
                         @NotNull @Min(0) Long afterSequence,
                         @NotNull @Min(1) @Max(256) Integer limit) {
    }
}
