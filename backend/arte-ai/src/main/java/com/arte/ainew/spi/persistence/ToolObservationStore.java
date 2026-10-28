package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolCharge;
import com.arte.ainew.pojo.tool.ToolObservation;
import com.arte.ainew.pojo.tool.ToolObservationMetadata;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 工具执行记录与统计扫描/加载
 * <p>
 * 内部只读存储；返回值必须经当前 Invocation 读取资格过滤后才能对外投影。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public interface ToolObservationStore {

    record Position(long acceptedAt, String key) {
    }

    record Candidate(String key, long acceptedAt, ToolObservationMetadata metadata) {
    }

    record Scan(List<Candidate> records, boolean more, boolean historicalIncomplete) {
    }

    record Entry(Candidate candidate, Invocation invocation, List<Attempt> attempts,
                 Map<String, BudgetReservation> reservations, Map<String, ToolCharge> charges,
                 Map<String, BudgetSettlement> settlements, ToolApproval approval) {
    }

    Mono<Scan> scan(ExecutionOwner owner, ToolObservation.Query query, Position after, int limit);

    /**
     * 每批最多 20 条；Invocation、Attempt、审批及账本在同一只读事务内读取，不读取结果内容。
     */
    Mono<List<Entry>> load(ExecutionOwner owner, List<Candidate> candidates);
}
