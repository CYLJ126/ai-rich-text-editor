package com.arte.base.api.security;

/**
 * 资料外发策略。
 *
 * <p>判断资料用途、发送范围和目的地是否允许；与资源权限及任务授权共同约束外发，不因拥有阅读权限而默认允许发送。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface EgressPolicy {
}
