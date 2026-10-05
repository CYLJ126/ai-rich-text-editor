package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.pojo.generation.ModelResult;
import reactor.core.publisher.Flux;

/**
 * 生成供应商的流映射扩展；非流结果使用 ProviderAdapter.mapResult。
 * 聚合工具参数、用量及完整输出，在同一次上游订阅中产生唯一 Result／Failure；
 * 不内部 subscribe、cache 无界输出或重新调用供应商，缺失结束信号不得伪装成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public interface GenerationProviderAdapter<Q, S> extends ProviderAdapter<GenerationRequest, Q, S, ModelResult> {

    Flux<GenerationSignal> mapStream(Flux<S> responses, GatewayCall<GenerationRequest> call);
}
