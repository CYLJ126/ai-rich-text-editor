package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolChatCommands;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.pojo.tool.ToolChatStep;
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

    Mono<StoreOutcome<ToolChatRun>> accept(ToolChatRunSnapshot snapshot, Consumer<ToolChatRunSnapshot> authorization);

    Mono<ToolChatRun> find(ExecutionOwner owner, String runId);

    Mono<ToolChatRunSnapshot> snapshot(ExecutionOwner owner, String runId);

    Mono<List<ToolChatStep>> steps(ExecutionOwner owner, String runId);

    Mono<List<ToolChatCommands.Lease>> claim(String workerId, int limit, Duration leaseDuration);

    Mono<StoreOutcome<ToolChatCommands.Lease>> renew(ToolChatCommands.Lease lease, Duration duration);

    Mono<StoreOutcome<ToolChatCommands.Receipt>> advance(ToolChatCommands.Advance command, Consumer<ToolChatCommands.Guard> authorization);

    /**
     * 只唤醒原 Run，不创建子调用、不推进状态；持锁时到达的信号不能被稍后的 park 覆盖。
     */
    Mono<StoreOutcome<ToolChatRun>> wake(ExecutionOwner owner, String runId);
}
