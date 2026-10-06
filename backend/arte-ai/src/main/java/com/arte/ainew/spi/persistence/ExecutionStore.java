package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 执行存储服务
 * 明确保存 Invocation／Attempt、归属、版本、结果引用；输出批次与重放游标归 EventStore。
 * <p>
 * 主要操作：Invocation／Attempt 记录、条件更新、关联工作项与结果查询等。
 * 边界：AI 定义状态契约，基础设施实现；Job 调度状态由公共 JobScheduler 管理，Run 历史另有权威。
 * <p>
 * 方法落实幂等作用域／摘要唯一约束、version 与 fencingToken 条件更新、Attempt 序号唯一性，
 * 以及终态／结果引用／Outbox 的提交边界。UNKNOWN 仅经远端核对收敛，不重新派发。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:40 ✾
 **/
public interface ExecutionStore {
    /**
     * 原子提交幂等记录、Invocation、可选 Turn／会话 CAS、首事件和派发 Outbox；成功才可返回 Accepted。
     */
    Mono<StoreOutcome<Invocation>> accept(ExecutionCommands.Accept command);

    /**
     * 当前授权归属不匹配与不存在均不返回对象；调用者仍负责校验权限／grant。
     */
    Mono<Invocation> find(ExecutionOwner owner, String invocationId);

    /**
     * 按耐久受理作用域读取原调用，供应用在会话版本／时间检查之前核对幂等摘要；不授予访问权。
     */
    Mono<Invocation> findAccepted(ExecutionOwner owner, String capabilityId, String idempotencyKey);

    Mono<Attempt> findAttempt(ExecutionOwner owner, String invocationId, String attemptId);

    Mono<StoreOutcome<Attempt>> createAttempt(ExecutionCommands.CreateAttempt command);

    Mono<StoreOutcome<Attempt>> acquireLease(ExecutionCommands.AcquireLease command);

    /**
     * 续租也作版本比较；失败后停止发送、追加和提交，不能认为仍拥有执行权。
     */
    Mono<StoreOutcome<Attempt>> renewLease(ExecutionCommands.Guard guard, Duration lease);

    /**
     * 原子停止数据库时钟下已失效的活跃 Attempt；未发送收敛为 INTERRUPTED，可能发送收敛为 UNKNOWN。
     * 推进版本／fencing 并提交终态及事件，不接管或重发；费用由预算端口独立处理。
     */
    Mono<StoreOutcome<Invocation>> stopExpired(ExecutionCommands.Version target);

    Mono<StoreOutcome<Attempt>> markDispatch(ExecutionCommands.Dispatch command);

    Mono<StoreOutcome<Attempt>> updateConditionally(ExecutionCommands.FailAttempt command);

    /**
     * 不提供 save(finalSnapshot)：只有此边界可提交终态、结果引用、事件及 Outbox。
     */
    Mono<StoreOutcome<Invocation>> commitCompletion(ExecutionCommands.Complete command);

    Mono<StoreOutcome<Invocation>> commitCompletion(ExecutionCommands.CompleteBeforeAttempt command);
}
