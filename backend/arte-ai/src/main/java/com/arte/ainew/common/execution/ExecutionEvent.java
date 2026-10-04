package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化、可重放的平台事件。sequence 在同一 executionId 内跨 Attempt 单调递增，非全局序号。
 * payloadType 与 payloadVersion 由受信编解码注册表解析，禁止使用客户端指定的 Java 类名反序列化。
 * 构造此对象不代表已提交；EventStore 提交后才可发布，订阅不重新执行任务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionEvent<P extends ExecutionEvent.Payload>(
        int schemaVersion, String executionId, String attemptId, long sequence,
        Kind kind, Instant occurredAt, String payloadType, int payloadVersion, P payload) implements Serializable {
    public enum Kind { ACCEPTED, STARTED, OUTPUT, CHECKPOINT, CONTROL, TERMINAL }

    /** 扩展由所属模块提供；实现必须深度不可变、无运行资源且在编解码注册表中登记。 */
    public interface Payload extends Serializable {
        Kind eventKind();
    }

    public ExecutionEvent {
        ContractChecks.range(schemaVersion, "schemaVersion", 1, Integer.MAX_VALUE);
        ContractChecks.id(executionId, "executionId");
        ContractChecks.optionalId(attemptId, "attemptId");
        ContractChecks.range(sequence, "sequence", 1, Long.MAX_VALUE);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        ContractChecks.id(payloadType, "payloadType");
        ContractChecks.range(payloadVersion, "payloadVersion", 1, Integer.MAX_VALUE);
        Objects.requireNonNull(payload, "payload");
        ContractChecks.require(kind == payload.eventKind(), "Event kind does not match payload");
    }

    /** afterSequence 为排他重放位置；0 表示从首个保留事件开始，游标不是访问凭据。 */
    public record Cursor(String executionId, long afterSequence) implements Serializable {
        public Cursor {
            ContractChecks.id(executionId, "executionId");
            ContractChecks.range(afterSequence, "afterSequence", 0, Long.MAX_VALUE);
        }
    }
}
