package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * TOOL 受理的同库短事务；快照、通用 Invocation／受理索引／事件及 DISPATCH Outbox 一起提交，不调用工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public interface ToolAdmissionStore {

    /**
     * 内部重放定位，不授予读取权；服务必须重新授权后才返回回执。
     */
    Mono<Accepted> findAccepted(ExecutionOwner owner, String bindingId, String idempotencyKey);

    /**
     * 内部派发／审计读取；owner 不匹配或不存在返回空，损坏通过 onError 传播。
     */
    Mono<ToolExecutionSnapshot> snapshot(ExecutionOwner owner, String invocationId);

    /**
     * 原子受理或返回原调用；当前授权与治理检查覆盖首次提交和重放，任一写入／检查失败整体回滚。
     * authorization 必须在真实事务执行，不可在方法外预先运行后传空回调。
     */
    Mono<StoreOutcome<Accepted>> accept(ToolExecutionSnapshot snapshot, Consumer<ToolExecutionSnapshot> authorization);

    /**
     * 新受理在持锁等待或写入期间耗尽总期限；必须回滚，历史回执重放不受原期限限制。
     */
    final class DeadlineExceeded extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public DeadlineExceeded() {
            super("Tool admission deadline exceeded");
        }
    }

    /**
     * 快照和当前调用状态来自同一受理身份，重放不覆盖原版本、期限、配置或参数。
     */
    record Accepted(Invocation invocation, ToolExecutionSnapshot snapshot) {
        public Accepted {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(snapshot);
            if (!invocation.request().equals(snapshot.request()) || !invocation.requestDigest().equals(snapshot.requestDigest()))
                throw new IllegalArgumentException("Tool acceptance does not match snapshot");
        }
    }
}
