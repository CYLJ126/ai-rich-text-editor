package com.arte.base.spi.coordination;

/**
 * 执行归属与租约协调。
 *
 * <p>协调任务领取、续租、失效及 fencing token；过期持有者不得继续提交，租约不能替代领域事务和唯一约束。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface LeaseCoordinator {
}
