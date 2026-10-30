package com.arte.ainew.spi.control;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.*;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 发布内容读取端口
 * <p>
 * 提供：发布的能力、绑定、连接和费率定义。
 * 精确版本不可变；当前资源使用授权由独立业务入口复核。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public interface PublishedAiCatalog {

    /**
     * 在一次读取范围内复用一致目录与预算定义；不把展示快照升级为写入授权。
     */
    <T> Mono<T> inSnapshot(ExecutionContext context, Function<PublishedAiDefinitions, Mono<T>> work);

    /**
     * 按预算预留记录中固定的费率版本，取得结算所需的原始费率定义。
     * 例如，预留时固定为 rate/v1，之后发布了 rate/v2，这次结算仍使用 v1。
     * <p>
     * 仅供已有耐久执行／预留的结算读取原费率。调用者必须先核对可信记录及归属；
     * 不要求仍有新调用资格，不解析 latest、不回退其他版本，不向普通 HTTP 开放。
     * <p>
     * 独立于当前模型调用资格，即使用户被撤权或模型停用后，系统仍能处理已经发生的费用。
     */
    Mono<RateDefinition> historicalRate(DefinitionRef reference);

    default Mono<PublishedAiDefinitions> read(ExecutionContext context) {
        return inSnapshot(context, Mono::just);
    }

    default Mono<RateDefinition> rate(DefinitionRef reference, ExecutionContext context) {
        return read(context).map(value -> value.rate(reference)
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH)));
    }

    default Mono<ResolvedBinding> binding(DefinitionRef reference, ExecutionContext context) {
        return read(context).map(value -> {
            reference.requireType("binding");
            return value.bindings().stream().filter(binding -> binding.definition().equals(reference)).findFirst()
                    .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        });
    }

    default Mono<ConnectionDefinition> connection(DefinitionRef reference, ExecutionContext context) {
        return read(context).map(value -> {
            reference.requireType("connection");
            return value.connections().stream().filter(connection -> connection.definition().equals(reference)).findFirst()
                    .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        });
    }

    default Mono<CapabilityDescriptor> capability(DefinitionRef reference, ExecutionContext context) {
        return read(context).map(value -> {
            reference.requireType("capability");
            return value.capabilities().stream().filter(capability -> capability.definition().equals(reference)).findFirst()
                    .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        });
    }
}
