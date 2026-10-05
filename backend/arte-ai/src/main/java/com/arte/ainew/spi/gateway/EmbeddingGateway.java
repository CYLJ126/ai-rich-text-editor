package com.arte.ainew.spi.gateway;

import com.arte.ainew.pojo.embedding.EmbeddingRequest;
import com.arte.ainew.pojo.embedding.EmbeddingResult;
import com.arte.ainew.pojo.execution.GatewayCall;
import reactor.core.publisher.Mono;

/**
 * 向量网关
 * <p>
 * 主要操作：embed、向量与版本结果
 * 边界：向量专有契约，与聊天输出分开
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:00 ✾
 **/
public interface EmbeddingGateway {

    /**
     * 一次受治理的向量请求；核对输入顺序／身份、维度及向量空间，不自行重试。
     */
    Mono<EmbeddingResult> embed(GatewayCall<EmbeddingRequest> call);
}
