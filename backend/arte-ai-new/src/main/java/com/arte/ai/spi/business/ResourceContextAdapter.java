package com.arte.ai.spi.business;

/**
 * 可选业务资料提供。
 *
 * <p>按授权资源、版本、草稿及范围输出内容和来源；由组合模块调用所属领域的查询端口，AI 核心不直接访问业务数据库。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §2.7。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ResourceContextAdapter {
}
