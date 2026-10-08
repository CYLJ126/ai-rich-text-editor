package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 对账请求参数
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class ReconciliationRequests {

    private ReconciliationRequests() {
    }

    /**
     * 分页查询指定预算账户下的待对账记录。
     *
     * @param scope     租户与工作空间范围，必填；实际访问权限由后端根据当前认证身份校验
     * @param budgetRef 预算账户引用，必填且非空，最长 256 个字符；须属于当前身份可访问的账户
     * @param current   页码，必填，从 1 开始
     * @param size      每页记录数，必填，取值范围为 1～100
     */
    public record Query(@NotNull @Valid ConversationRequests.Scope scope, @NotBlank @Size(max = 256) String budgetRef,
                        @NotNull @Min(1) Long current, @NotNull @Min(1) @Max(100) Long size) {
    }

    /**
     * 人工确认远端执行已结束及实际费用，完成预算对账；须具备预算管理权限。
     * 本次确认的幂等键通过 HTTP 请求头 Idempotency-Key 传入。
     *
     * @param scope              租户与工作空间范围，必填；实际访问权限由后端根据当前认证身份校验
     * @param budgetRef          待对账的预算账户引用，必填且非空，最长 256 个字符；须与调用及预留记录所属账户一致
     * @param invocationId       待对账的模型调用 ID，必填且非空，最长 256 个字符
     * @param invocationVersion  查询待对账记录时取得的调用版本，必填，范围为 0～Long.MAX_VALUE - 1；用于拒绝过期确认
     * @param reservationId      该调用对应的预算预留 ID，必填且非空，最长 256 个字符
     * @param reservationVersion 查询待对账记录时取得的预留版本，必填，范围为 0～Long.MAX_VALUE - 1；用于拒绝过期确认
     * @param actualCharge       经账单或可信凭据核实的实际费用，必填；使用非负十进制字符串，整数最多 20 位、小数最多 18 位，
     *                           不支持科学计数法；允许明确核实后的零费用，不可因停止接收内容而直接按零费用处理
     * @param currency           实际费用的币种，必填，使用三位大写 ISO 4217 代码；须与预算账户及预留记录的币种一致
     * @param evidenceRef        账单或可信核对凭据的引用，必填且非空，最长 256 个字符，用于审计追溯
     * @param note               人工对账说明，必填且非空，最长 2000 个字符，写入审计记录
     * @param executionEnded     是否已核实远端执行结束，必填且必须为 true；仅停止接收流式内容不代表远端执行结束
     */
    public record Confirm(@NotNull @Valid ConversationRequests.Scope scope, @NotBlank @Size(max = 256) String budgetRef,
                          @NotBlank @Size(max = 256) String invocationId,
                          @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long invocationVersion,
                          @NotBlank @Size(max = 256) String reservationId,
                          @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long reservationVersion,
                          @NotBlank @Pattern(regexp = "[0-9]{1,20}(\\.[0-9]{1,18})?") String actualCharge,
                          @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                          @NotBlank @Size(max = 256) String evidenceRef, @NotBlank @Size(max = 2000) String note,
                          @NotNull @AssertTrue Boolean executionEnded) {
    }

    /**
     * 查询已持久化的人工对账回执，不触发新的费用结算。
     *
     * @param scope        租户与工作空间范围，必填；实际访问权限由后端根据当前认证身份校验
     * @param budgetRef    回执所属预算账户引用，必填且非空，最长 256 个字符；须属于当前身份可访问的账户
     * @param invocationId 原对账请求中的模型调用 ID，必填且非空，最长 256 个字符
     * @param key          原人工对账请求的 Idempotency-Key，必填且非空，最长 256 个字符；不是模型调用、停止或重新生成的幂等键
     */
    public record ReceiptQuery(@NotNull @Valid ConversationRequests.Scope scope,
                               @NotBlank @Size(max = 256) String budgetRef,
                               @NotBlank @Size(max = 256) String invocationId, @NotBlank @Size(max = 256) String key) {
    }
}
