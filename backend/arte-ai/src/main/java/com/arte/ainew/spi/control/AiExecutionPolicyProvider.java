package com.arte.ainew.spi.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import reactor.core.publisher.Mono;

/**
 * AI 执行政策提供者
 * <p>
 * 统一提供输入大小、输出 Token／字节、执行超时及快照保留限制。
 * <p>
 * 将限制来源与业务调用方解耦；有效限制与部署上限取交集，同一操作可复用读取，真实写入须在原事务内复核。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public interface AiExecutionPolicyProvider {

    /**
     * 异步取得当前执行上下文适用的有效政策，用于模型发现、上下文组装及执行预检。
     * 默认实现读取受信配置来源的 limits，并逐项与部署上限取较小值。
     * 同一操作可复用可信快照；实际写入须使用 {@link #inCurrentTransaction} 重新复核。
     *
     * @param context 可信执行上下文，确定本次政策适用的主体及租户／空间范围
     * @return 本次读取的有效执行限制
     */
    Mono<AiExecutionPolicy> current(ExecutionContext context);

    /**
     * 无原事务实现时拒绝，不允许把异步展示结果当作当前写入政策。
     */
    default AiExecutionPolicy inCurrentTransaction(ExecutionContext context) {
        throw new UnsupportedOperationException("Transactional execution policy is required");
    }
}
