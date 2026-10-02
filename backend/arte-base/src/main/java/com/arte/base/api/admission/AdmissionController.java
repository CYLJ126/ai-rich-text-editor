package com.arte.base.api.admission;

/**
 * 通用执行准入。
 *
 * <p>管理并发、速率、队列优先级和准入；采用有界等待，AI 费用预留与结算由 AI 层负责。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface AdmissionController {
}
