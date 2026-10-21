package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import reactor.core.publisher.Flux;

/**
 * 生成供应商的流映射扩展
 * <p>
 * 非流结果使用 ProviderAdapter.mapResult。
 * 聚合工具参数、用量及完整输出，在同一次上游订阅中产生唯一 Result／Failure；
 * 不内部 subscribe、cache 无界输出或重新调用供应商，缺失结束信号不得伪装成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public interface GenerationProviderAdapter<Q, S> extends ProviderAdapter<GenerationRequest, Q, S, ModelResult> {

    /**
     * 工具协议须显式实现，纯文本适配器不因能力声明带有 TOOL_CALLS 而自动开放。
     */
    default boolean supportsToolChat(com.arte.ainew.pojo.control.CapabilityDescriptor capability) {
        return false;
    }

    /**
     * 显式固定集合的协议入口；未实现工具协议的适配器默认拒绝。
     */
    default Q mapRequest(GatewayCall<GenerationRequest> call, ToolSetSnapshot tools) {
        if (tools != null) throw new UnsupportedOperationException("Tool protocol is not supported");
        return mapRequest(call);
    }

    default Flux<GenerationSignal> mapStream(Flux<S> responses, GatewayCall<GenerationRequest> call,
                                             ToolSetSnapshot tools) {
        if (tools != null) return Flux.error(new UnsupportedOperationException("Tool protocol is not supported"));
        return mapStream(responses, call);
    }

    Flux<GenerationSignal> mapStream(Flux<S> responses, GatewayCall<GenerationRequest> call);
}
