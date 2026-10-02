package com.arte.base.spi.change;

/**
 * 领域变更处理扩展。
 *
 * <p>由目标领域或组合模块提供预览、草稿补丁准备和正式资源应用；补丁 Schema、权限、版本校验、幂等及保存事务归目标领域。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3 的注册领域处理器边界。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ChangeSetHandler {
}
