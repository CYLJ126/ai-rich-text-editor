package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Run、检查点、步骤和独立唤醒工作项同库提交；内部端口的查询／领取不授予用户读取或子调用资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public interface ToolChatRunStore {

    /**
     * 内部幂等定位；仍须复核当前授权与原请求意图。
     */
    default Mono<ToolChatRun> accepted(ExecutionOwner owner, String idempotencyKey) {
        return Mono.error(new UnsupportedOperationException("Run replay lookup required"));
    }

    /**
     * 持租短暂休眠，不增加状态版本或生成空进展事件。
     */
    default Mono<StoreOutcome<ToolChatCommands.Lease>> park(ToolChatCommands.Lease lease, Duration delay) {
        return Mono.error(new UnsupportedOperationException("Run park required"));
    }

    /**
     * 停止事实、命令防重和唤醒同事务保存；不持根锁等待子 Invocation 锁。
     */
    default Mono<StoreOutcome<ToolChatRun>> cancel(ExecutionOwner owner, String runId, String commandKey,
                                                   Consumer<ToolChatCommands.Guard> authorization) {
        return Mono.error(new UnsupportedOperationException("Run cancellation required"));
    }

    default Mono<Boolean> cancellationRequested(ExecutionOwner owner, String runId) {
        return Mono.error(new UnsupportedOperationException("Run cancellation read required"));
    }

    Mono<StoreOutcome<ToolChatRun>> accept(ToolChatRunSnapshot snapshot, Consumer<ToolChatRunSnapshot> authorization);

    /**
     * 同一事务受理 Run、类型化 Turn、会话占用和通知；authorization 在持锁事务内复核资格。
     */
    Mono<StoreOutcome<ToolChatRun>> acceptChat(ToolChatRunSnapshot snapshot,
                                               Turn turn, long expectedConversationVersion,
                                               Consumer<ToolChatRunSnapshot> authorization);

    Mono<ToolChatLink> link(ExecutionOwner owner, String runId);

    /**
     * 独立 Run 游标，after 为排他序号，最多 100 条；不包含模型正文或工具参数。
     */
    Mono<List<ToolChatRunEvent>> events(ExecutionOwner owner, String runId, long after, int limit);

    /**
     * 已授权读取方可展示的按币种总预算；旧无预算 Run 返回空列表，不伪造零费用。
     */
    Mono<List<ToolChatBudget>> budgets(ExecutionOwner owner, String runId);

    Mono<ToolChatRun> find(ExecutionOwner owner, String runId);

    Mono<ToolChatRunSnapshot> snapshot(ExecutionOwner owner, String runId);

    /**
     * 固定子身份的单条输入读取，避免派发及账户选择扫描整个 Run 的历史上下文。
     */
    Mono<ToolChatStep> step(ExecutionOwner owner, String runId, String childExecutionId);

    Mono<List<ToolChatStep>> steps(ExecutionOwner owner, String runId);

    Mono<List<ToolChatCommands.Lease>> claim(String workerId, int limit, Duration leaseDuration);

    Mono<StoreOutcome<ToolChatCommands.Lease>> renew(ToolChatCommands.Lease lease, Duration duration);

    Mono<StoreOutcome<ToolChatCommands.Receipt>> advance(ToolChatCommands.Advance command, Consumer<ToolChatCommands.Guard> authorization);

    /**
     * 只唤醒原 Run，不创建子调用、不推进状态；持锁时到达的信号不能被稍后的 park 覆盖。
     */
    Mono<StoreOutcome<ToolChatRun>> wake(ExecutionOwner owner, String runId);
}
