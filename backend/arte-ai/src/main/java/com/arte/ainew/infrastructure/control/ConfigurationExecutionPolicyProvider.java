package com.arte.ainew.infrastructure.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import reactor.core.publisher.Mono;

/**
 * 数据库执行限制来源桥
 * <p>
 * 受信来源仅可收紧部署上限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ConfigurationExecutionPolicyProvider implements AiExecutionPolicyProvider {

    private final ControlConfigurationReader reader;
    private final AiExecutionPolicy ceiling;

    public ConfigurationExecutionPolicyProvider(ControlConfigurationReader reader, NewAiProperties.Limits ceiling) {
        this.reader = reader;
        this.ceiling = ExecutionPolicies.from(ceiling);
    }

    @Override
    public Mono<AiExecutionPolicy> current(ExecutionContext context) {
        return reader.read(context).map(value -> ExecutionPolicies.from(value.limits()).intersect(ceiling));
    }

    @Override
    public AiExecutionPolicy inCurrentTransaction(ExecutionContext context) {
        return reader.inCurrentTransaction(context, value -> ExecutionPolicies.from(value.limits()).intersect(ceiling));
    }
}
