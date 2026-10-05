package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

/**
 * 实际输入字节存储端口；独立于运行对象及 Invocation 权威，目前仅声明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public interface ContextSnapshotStore {
    /**
     * owner＋snapshotId 唯一；同内容返回 REPLAYED、不同内容返回 IDEMPOTENCY_CONFLICT，不覆盖旧输入。
     */
    Mono<StoreOutcome<ContextSnapshot>> put(ExecutionOwner owner, ContextSnapshot snapshot);

    /**
     * 必须带 owner 读取；不存在返回 empty，应用边界转换为明确错误并重新授权。
     */
    Mono<ContextSnapshot> find(ExecutionOwner owner, String snapshotId);
}
