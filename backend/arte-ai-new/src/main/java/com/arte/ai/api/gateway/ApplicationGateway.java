package com.arte.ai.api.gateway;

/**
 * 远程应用与 Agent 调用。
 *
 * <p>调用远程应用、继续远端会话并查询任务；保留远端身份、生命周期及控制能力，按主体隔离会话，不复用模型结果语义。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.5。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ApplicationGateway {
}
