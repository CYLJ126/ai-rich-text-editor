package com.arte.ainew.spi.control;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 预算定义提供器
 * <p>
 * 提供：当前主体在指定空间、指定操作下可访问的预算定义，不读写账户或扩大额度。
 * 调用入口仍须执行当前读／写授权；此端口不替代账户初始化或受理事务门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public interface BudgetDefinitionProvider {

    enum Operation {READ, INITIALIZE, EXECUTE}

    Mono<List<BudgetDefinition>> available(ExecutionContext context, Operation operation);

    /**
     * 原同库写事务中同步复核定义与当前使用资格；不支持时拒绝，禁止阻塞异步读取或复用预检允许结论。
     */
    default BudgetDefinition resolveInCurrentTransaction(String budgetRef, ExecutionContext context, Operation operation) {
        throw new UnsupportedOperationException("Transactional budget definition is required");
    }

    default Mono<BudgetDefinition> resolve(String budgetRef, ExecutionContext context, Operation operation) {
        return available(context, operation).map(values -> values.stream()
                .filter(value -> value.budgetRef().equals(budgetRef)).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE)));
    }
}
