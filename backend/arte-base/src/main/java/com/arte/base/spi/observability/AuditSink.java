package com.arte.base.spi.observability;

/**
 * 审计记录接收。
 *
 * <p>记录授权、外发、修改、配置变更等审计事实；明确耐久、完整性及保留要求，默认不记录凭据或敏感全文。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface AuditSink {
}
