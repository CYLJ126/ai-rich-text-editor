package com.arte.base.spi.observability;

import com.arte.base.model.observability.AuditReceipt;
import com.arte.base.model.observability.AuditRecord;

/**
 * 审计记录接收。
 *
 * <p>记录授权、外发、修改、配置变更等审计事实；明确耐久、完整性及保留要求，默认不记录凭据或敏感全文。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 不提供无操作默认实现；关键审计写入失败由所属业务决定拒绝或进入明确故障流程。
 */
public interface AuditSink {
    /**
     * 按 eventId 去重，相同 ID 不同事实必须拒绝。同步持久化须在专用执行资源上调用。
     */
    AuditReceipt append(AuditRecord record);
}
