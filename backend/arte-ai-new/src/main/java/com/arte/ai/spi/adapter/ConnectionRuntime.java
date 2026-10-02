package com.arte.ai.spi.adapter;

/**
 * 连接运行支撑。
 *
 * <p>管理网络连接或受管进程、凭据解析、协议会话及资源清理；按主体和协议复用或隔离，处理停用、失效与重建，不授予用户权限。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.7。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ConnectionRuntime {
}
