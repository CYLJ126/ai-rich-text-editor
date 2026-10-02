package com.arte.ai.spi.adapter;

/**
 * 供应商能力适配。
 *
 * <p>探测供应商能力，映射参数并转换请求、结果、错误和用量；声明 SDK 及能力矩阵，缺失必需特性时拒绝或明确降级。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.7。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ProviderAdapter {
}
