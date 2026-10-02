package com.arte.ai.spi.store;

/**
 * 耐久输出与事件存储。
 *
 * <p>保存输出批次、检查点、游标及终态事件，提供可重放读取；实现可替换，保留和归档须明确，不作为业务正文存储。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ExecutionEventStore {
}
