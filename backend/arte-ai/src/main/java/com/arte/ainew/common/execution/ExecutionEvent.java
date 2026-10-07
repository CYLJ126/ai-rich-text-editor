package com.arte.ainew.common.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 平台执行事件，统一封装调用标识、事件序号、类型、时间以及具体业务负载。
 * <p>
 * 存储层在同一 executionId 内分配跨 Attempt 单调递增的 sequence，提交后可按序重放。
 * 事件标识由 executionId 与 sequence 共同确定；不同调用的序号不能比较，发生时间也不能替代序号排序。
 * 构造此对象仅完成结构校验，不代表事件已保存或事务已提交；事件与 EVENT Outbox 提交后才可发布通知。
 * 订阅及重放读取已提交事件，不重新执行模型调用；重复通知可通过调用标识与序号去重。
 * <p>
 * 事件信封版本与负载版本分别演进。存储编解码器只接受已登记的稳定类型别名及支持的版本，
 * 禁止依据客户端指定的 Java 类名反序列化。读取仍需校验调用归属和权限，事件标识本身不提供授权。
 *
 * @param <P>            具体业务负载类型，必须实现 Payload 契约，并由编解码器登记后才能持久化及重放
 * @param schemaVersion  事件信封的结构版本，至少为 1；描述本 record 的格式，当前存储编解码器支持版本 1
 * @param executionId    平台调用标识，在 AI 模块中对应 Invocation ID；不是会话 ID 或供应商请求 ID
 * @param attemptId      产生事件的执行尝试标识，可为 null，例如 ACCEPTED 事件产生时尚未创建 Attempt；
 *                       同一次调用可包含多个 Attempt，事件序号不会随 Attempt 切换而重置
 * @param sequence       同一调用内由存储层分配的事件序号，从 1 开始；用于有序重放、去重和构造读取游标，
 *                       不是 Invocation 版本、Attempt 版本或全局序号
 * @param kind           平台事件类别，不可为 null，必须与 payload.eventKind() 一致；便于消费者选择处理逻辑
 * @param occurredAt     平台记录该事件发生的时间，不可为 null；不是数据库提交时间或通知送达时间，排序以 sequence 为准
 * @param payloadType    负载的稳定类型别名，例如 status、output-batch、terminal、budget-changed；
 *                       由编解码器白名单映射到具体类型，不是 Java 类名，同一负载类型可对应不同事件类别
 * @param payloadVersion 该负载类型的数据结构版本，至少为 1；与 schemaVersion 分开演进，当前存储编解码器支持版本 1
 * @param payload        具体业务事实，不可为 null，例如输出批次、状态或预算变更；内容须符合所属模块的负载契约，
 *                       当前 SSE 通知只发送调用标识、序号及类别，不直接发送本字段
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ExecutionEvent<P extends ExecutionEvent.Payload>(
        int schemaVersion, String executionId, String attemptId, long sequence,
        Kind kind, Instant occurredAt, String payloadType, int payloadVersion, P payload) implements Serializable {
    /**
     * 平台事件类别；描述本条事件的业务含义，不直接代表调用当前状态。
     */
    public enum Kind {
        /**
         * 调用已受理，通常尚未创建执行尝试。
         */
        ACCEPTED,
        /**
         * 执行尝试已启动，调用进入 RUNNING 状态。
         */
        STARTED,
        /**
         * 已保存的模型输出批次，可包含文本增量、工具调用增量或用量等事实。
         */
        OUTPUT,
        /**
         * 执行过程中的其他非终态状态或检查点事实，不是聊天上下文快照。
         */
        CHECKPOINT,
        /**
         * 暂停、恢复、取消等控制操作的回执事实。
         */
        CONTROL,
        /**
         * 调用已进入终态，负载提供终态及可用结果引用、错误事实；预算结算可能尚未完成。
         */
        TERMINAL,
        /**
         * 预算预占或结算状态已变更，可在 TERMINAL 之后产生。
         */
        BUDGET_CHANGED
    }

    /**
     * 执行事件的业务负载契约，由所属业务模块提供具体实现。
     * <p>
     * ExecutionEvent 提供公共标识与重放位置，Payload 保存某一类事件的具体业务事实。
     * AI 模块当前通过 ExecutionPayload 实现输出批次、状态、控制回执、终态及预算变更等负载；
     * 负载可以保存结果引用，不要求包含完整模型回复，也不代表一次调用的全部上下文。
     * <p>
     * 实现必须深度不可变：嵌套集合及对象也不能在构造后被修改，并应在构造时校验内容及大小限制。
     * 只保存可编码、可重放的数据，不持有连接、线程、流、订阅、回调或其他运行资源。
     * 新增实现时须同时在受信编解码器中登记具体类型、稳定别名和支持的版本；
     * 仅实现 Serializable 并不能获得持久化支持，也不允许使用任意 Java 类名或不可信 Java 序列化数据恢复对象。
     * <p>
     * 本接口不自动保证深度不可变或检查编解码登记，这些约束由实现类及编解码器落实；
     * ExecutionEvent 构造器会检查负载非空，以及 eventKind() 与事件 kind 是否一致。
     */
    public interface Payload extends Serializable {
        /**
         * 返回该负载对应的平台事件类别；允许由内容决定，例如状态负载按状态返回 ACCEPTED、STARTED 或 CHECKPOINT。
         *
         * @return 非 null 的事件类别，必须与封装此负载的 ExecutionEvent.kind 一致
         */
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

    /**
     * 单次平台调用的事件读取游标，用于分页重放以及断线后继续订阅。
     * <p>
     * afterSequence 是排他位置，只读取 sequence 大于该值的事件。例如已处理到 12，下一次传 12，
     * 不会重复读取序号 12。分页读取使用存储层返回的 next 游标；实时消费者可在处理事件后记录该事件序号，
     * 不应把 Redis 唤醒提示中的序号直接当作已处理位置，否则可能跳过尚未读取的事件。
     * <p>
     * 0 表示尚未读取，历史未裁剪时从首个事件开始。若位置低于存储层记录的裁剪边界，
     * 重放返回 CURSOR_EXPIRED，调用者应读取调用状态及结果等持久化快照恢复，不能静默跳过缺失历史。
     * 游标仅描述读取位置，不包含租户、用户身份或授权信息；每次读取仍须校验调用归属和权限。
     *
     * @param executionId   要重放的平台调用标识，与事件 executionId 一致；序号只能在该调用内解释
     * @param afterSequence 已读取位置的事件序号，至少为 0；后续读取仅返回大于该值的事件，0 的唤醒提示不会重置本字段
     */
    public record Cursor(String executionId, long afterSequence) implements Serializable {
        public Cursor {
            ContractChecks.id(executionId, "executionId");
            ContractChecks.range(afterSequence, "afterSequence", 0, Long.MAX_VALUE);
        }
    }
}
