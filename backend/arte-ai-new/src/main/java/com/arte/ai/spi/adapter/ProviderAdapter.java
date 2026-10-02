package com.arte.ai.spi.adapter;

import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelPlan;
import com.arte.ai.model.generation.PreparedModelCall;

/**
 * 供应商映射；准备阶段固定参数与完整发送正文，协议层禁止隐式重试。
 */
public interface ProviderAdapter {
    boolean supports(ModelPlan plan);

    PreparedModelCall prepare(ModelPlan plan, GenerationRequest request);
}
