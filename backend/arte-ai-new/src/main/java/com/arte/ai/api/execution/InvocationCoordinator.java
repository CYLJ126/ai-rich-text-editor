package com.arte.ai.api.execution;

/**
 * 单次能力执行协调。
 *
 * <p>统一受理、准入、派发、尝试记录、重试协调及结算；类型化执行委托给对应网关，普通调用不强制经过 Workflow 或 Agent。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.5。
 * 平台服务采用组合与委托，暂不引入实现继承。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public class InvocationCoordinator {
}
