package com.arte.ai.spi.store;

/**
 * AI 调用与尝试存储。
 *
 * <p>保存 Invocation、Attempt，支持条件更新、关联工作项及结果查询；不接管公共 Job 调度状态或编排引擎的 Run 历史。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ExecutionStore {
}
