package com.arte.ai.spi.business;

/**
 * 可选业务工具适配。
 *
 * <p>将领域查询和命令暴露为受限工具；由组合模块调用实际领域端口，保留权限、版本及事务规则，不直接修改领域数据库。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.7。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface BusinessToolAdapter {
}
