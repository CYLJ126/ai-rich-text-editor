package com.arte.ainew.pojo.budget;

import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Instant;
import java.util.Objects;

/**
 * 账单人工核对契约
 * <p>
 * 只能由重新授权的预算管理员组装，所有金额使用十进制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    /**
     * 财务核对所需的最小调用／预留关联，不包含正文、模型结果或密钥。
     *
     * @param invocationId       原调用标识
     * @param conversationId     本人读取可见的关联会话；管理读取置空
     * @param state              本地执行状态
     * @param invocationVersion  原调用的条件确认版本
     * @param reservationId      原预留标识
     * @param reservationVersion 预留条件确认版本
     * @param budgetRef          精确账户标识
     * @param reserved           实际预留金额及币种
     * @param remoteRequestId    可用的远端安全关联标识
     * @param acceptedAt         原调用受理时间
     * @param pendingSince       最早待核对结算事实时间；尚未结算处理的终态预留使用本地终态时间
     */
    public record Pending(String invocationId, String conversationId, Invocation.State state, long invocationVersion,
                          String reservationId, long reservationVersion, String budgetRef, Money reserved,
                          String remoteRequestId, Instant acceptedAt, Instant pendingSince) {
        public Pending(String invocationId, String conversationId, Invocation.State state, long invocationVersion,
                       String reservationId, long reservationVersion, String budgetRef, Money reserved, String remoteRequestId, Instant acceptedAt) {
            this(invocationId, conversationId, state, invocationVersion, reservationId, reservationVersion, budgetRef, reserved, remoteRequestId, acceptedAt, null);
        }
    }

    /**
     * 凭实际账单及执行结束证据提交的人工核对命令，目标账本归属与真实核对者分别记录。
     *
     * @param owner              目标账本所属执行主体
     * @param reviewer           真实核对者身份，不能替换为目标账户所有者
     * @param traceId            本次核对操作的追踪标识
     * @param key                本次核对操作的幂等键
     * @param budgetRef          精确账户标识
     * @param invocationId       原调用标识
     * @param invocationVersion  原调用的条件确认版本
     * @param reservationId      原预留标识
     * @param reservationVersion 预留条件确认版本
     * @param charge             有证据支持的实际费用及币种，零费用也须有证据
     * @param evidenceRef        可归属原调用的账单及核对证据引用
     * @param note               人工核对说明，最长 2000 字符
     * @param executionEnded     是否已确认远端执行结束，提交时必须为真
     */
    public record Confirm(ExecutionOwner owner, ExecutionPrincipal reviewer, String traceId, String key,
                          String budgetRef, String invocationId, long invocationVersion, String reservationId,
                          long reservationVersion,
                          Money charge, String evidenceRef, String note, boolean executionEnded) {
        public Confirm {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(reviewer);
            Objects.requireNonNull(charge);

            ContractChecks.id(traceId, "traceId");
            ContractChecks.id(key, "key");
            ContractChecks.id(budgetRef, "budgetRef");
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.id(reservationId, "reservationId");
            ContractChecks.range(invocationVersion, "invocationVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.range(reservationVersion, "reservationVersion", 0, Long.MAX_VALUE - 1);
            ContractChecks.id(evidenceRef, "evidenceRef");
            ContractChecks.text(note, "note", 2000);
            ContractChecks.require(executionEnded, "Bill verification must confirm execution has ended");
        }
    }

    /**
     * 不可变核对回执，与费用、执行收敛、门闩及事件在同一事务保存。
     * targetOwner 记录真实目标，reviewer 始终记录真实操作者；authorizationRef 保存具体账户授权依据。
     * 旧回执缺少新增审计字段时保留原事实，不推造历史授权。
     */
    public record Receipt(String key, String invocationId, String reservationId, String budgetRef, Money charge,
                          String evidenceRef, String note, ExecutionPrincipal reviewer, String traceId,
                          Invocation.State state, long invocationVersion, long reservationVersion,
                          Instant confirmedAt, ExecutionOwner targetOwner, String authorizationRef) {
        /**
         * 保留旧内部调用构造方式；新核对路径总是记录真实目标和授权依据。
         */
        public Receipt(String key, String invocationId, String reservationId, String budgetRef, Money charge,
                       String evidenceRef, String note, ExecutionPrincipal reviewer, String traceId,
                       Invocation.State state, long invocationVersion, long reservationVersion, Instant confirmedAt) {
            this(key, invocationId, reservationId, budgetRef, charge, evidenceRef, note, reviewer, traceId, state,
                    invocationVersion, reservationVersion, confirmedAt, null, null);
        }
    }
}
