package com.arte.ainew.spi.control;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;

/**
 * 当前模型使用资格的统一发现与解析端口
 * <p>
 * 结果绑定完整执行范围和精确发布引用。
 * 预算、实际网关支持及执行政策另行校验，不将可发现或历史快照当作后续外发许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public interface AiResourceEntitlementService {

    /**
     * 有界的 model:use 资格发现；limit 为 1～256，同一绑定的不同版本分别保留。
     */
    Flux<AiEntitlement> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context);

    Mono<AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability, Action action, ExecutionContext context);

    default Mono<AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context) {
        return resolve(binding, capability, MODEL_USE, context);
    }

    /**
     * 连接只能通过当前有使用资格的模型绑定获得，不能直接凭发布目录读取结果授权外发。
     */
    default Mono<ConnectionDefinition> resolveConnection(DefinitionRef connection, ExecutionContext context) {
        return Mono.defer(() -> {
            connection.requireType("connection");
            return discover(CapabilityDescriptor.Kind.GENERATION, 256, context)
                    .filter(value -> value.connection().definition().equals(connection)).map(AiEntitlement::connection).next()
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE)));
        });
    }

    default Mono<CapabilityDescriptor> resolveCapability(DefinitionRef capability, ExecutionContext context) {
        return Mono.defer(() -> {
            capability.requireType("capability");
            return discover(CapabilityDescriptor.Kind.GENERATION, 256, context)
                    .map(value -> value.binding().capability()).filter(value -> value.definition().equals(capability)).next()
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE)));
        });
    }
}
