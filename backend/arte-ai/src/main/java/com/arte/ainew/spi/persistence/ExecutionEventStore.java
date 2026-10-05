package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * 执行事件存储服务
 * <p>
 * 主要操作：保存输出批次、检查点、游标与终态事件等。
 * 边界：AI 定义重放契约，存储实现可替换，不作为文章正文存储。
 * <p>sequence 在 executionId 内跨 Attempt 单调递增；游标为排他读取位置，不含访问凭据。
 * 输出批次保存后才可发布，编解码仅接受已登记负载类型及版本，重放不执行供应商调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:41 ✾
 **/
public interface ExecutionEventStore {
    /** 在 executionId 内分配序号并原子提交整个批次、幂等记录及发布 Outbox，不在事务内推送。 */
    Mono<StoreOutcome<List<ExecutionEvent<?>>>> appendBatch(ExecutionCommands.Append command);

    /** 排他游标、有界单页；CURSOR_EXPIRED 要求调用者读取权威快照，不静默跳过已裁剪事件。 */
    Mono<StoreOutcome<Page>> replay(ExecutionOwner owner, ExecutionEvent.Cursor cursor, int limit);

    /** 按保留策略裁剪已确认发布、确定终态的历史，保留权威快照及过期游标边界。 */
    Mono<StoreOutcome<ExecutionEvent.Cursor>> discardThrough(ExecutionOwner owner, String invocationId, long throughSequence);

    record Page(List<ExecutionEvent<?>> events,
                ExecutionEvent.Cursor next, long retainedAfterSequence) {
        public Page {
            events = List.copyOf(events);
            Objects.requireNonNull(next, "next");
            ContractChecks.range(retainedAfterSequence, "retainedAfterSequence", 0, Long.MAX_VALUE);
        }
    }
}
