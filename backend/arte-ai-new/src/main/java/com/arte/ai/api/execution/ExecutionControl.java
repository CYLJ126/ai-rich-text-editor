package com.arte.ai.api.execution;

/**
 * 执行查询与控制。
 *
 * <p>查询状态、请求取消，并在能力支持时暂停或继续；分派到 Invocation、公共 Job 或 Run 的状态权威，取消请求不等于取消完成。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.6。
 * 平台服务采用组合与委托，暂不引入实现继承。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public class ExecutionControl {
}
