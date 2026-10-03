package com.arte.ai.spi.store;

import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.execution.ModelExecution;
import com.arte.ai.model.execution.ModelSubmission;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.identity.ExecutionScope;

import java.util.List;
import java.util.Optional;

/**
 * 模型受理／尝试权威存储。受理与预算预留、终态与最终结果／事件／结算各须同事务提交。
 */
public interface ExecutionStore {
    record Acceptance(ModelExecution execution, boolean created) {
    }

    /**
     * 登记执行、预留预算
     *
     * @param submission 执行提交
     * @param quote      预算报价单
     * @return 执行受理结果
     */
    Acceptance accept(ModelSubmission submission, BudgetQuote quote);

    Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key, String digest);

    /**
     * 服务端稳定键的受理核对；调用方必须重新授权并验证所属业务关联，不能据此重新派发。
     */
    Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key);

    Optional<ModelExecution> find(ExecutionScope scope, String executionId);

    /**
     * 同一主体范围内批量读取；持久化实现应使用一次查询，不隐式授予结果读取权限。
     */
    default List<ModelExecution> findAll(ExecutionScope scope, List<String> executionIds) {
        return executionIds.stream().distinct().flatMap(id -> find(scope, id).stream()).toList();
    }

    boolean start(ExecutionScope scope, String executionId);

    void markDispatched(ExecutionScope scope, String executionId);

    void finish(ExecutionScope scope, String executionId, ExecutionStatus status, ModelResult result, ExecutionError error);

    /**
     * 先耐久提交输出，再允许订阅者查询；不改变执行终态。
     */
    default void appendDelta(ExecutionScope scope, String executionId, String text) {
        throw new UnsupportedOperationException("durable streaming unavailable");
    }
}
