package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationBudgetState;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 已授权归属内的有界批量快照；缺失与不属于当前用户的 ID 均不返回。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 22:22 ✾
 */
public interface ExecutionReadStore {
    record Snapshot(Invocation invocation, InvocationBudgetState budgetState) {
    }

    Mono<List<Snapshot>> snapshots(ExecutionOwner owner, List<String> invocationIds);
}
