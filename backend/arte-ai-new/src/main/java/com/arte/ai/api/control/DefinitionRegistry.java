package com.arte.ai.api.control;

/**
 * 动作、策略与工作流定义管理。
 *
 * <p>按定义类型管理和校验动作、策略及工作流；保持各自 Schema 与版本，不成为无约束配置存储。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.4。
 * 平台服务采用组合与委托，暂不引入实现继承。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public class DefinitionRegistry {
}
