package com.arte.ai.api.gateway;

/**
 * 向量生成。
 *
 * <p>处理类型化向量请求，返回向量、维度及模型版本；与聊天输出分开，不混用不同模型的向量空间。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.5。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface EmbeddingGateway {
}
