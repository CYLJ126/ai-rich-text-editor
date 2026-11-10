package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 会话执行入口的时点只读投影
 * <p>
 * 占用变化不保证推进业务版本，不能用此投影替代受理事务。
 *
 * @param conversationId  已校验归属的会话 ID。
 * @param version         会话业务版本，对应提交的 expectedVersion。
 * @param activeExecution 当前活动执行；空闲时为 null，序列化时仍保留字段。
 * @param observedAt      数据库观察时间，仅供展示，不用于跨请求排序。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 11:30 ✾
 */
public record ConversationExecutionState(String conversationId, long version, ActiveExecution activeExecution,
                                         Instant observedAt) {

    public enum Kind {INVOCATION, RUN}

    /**
     * 带类型的真实执行标识，不向客户端返回存储哈希。
     *
     * @param kind        执行入口类型，子模型调用不能代替父 Run。
     * @param executionId 对应 Invocation 或 Run 的真实 ID。
     */
    public record ActiveExecution(Kind kind, String executionId) {

        public ActiveExecution {
            Objects.requireNonNull(kind, "kind");
            ContractChecks.id(executionId, "executionId");
        }
    }

    public ConversationExecutionState {
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
