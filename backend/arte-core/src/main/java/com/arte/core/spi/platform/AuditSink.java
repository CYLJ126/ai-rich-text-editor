package com.arte.core.spi.platform;

import com.arte.core.pojo.platform.AuditEntry;

/**
 * 同事务写治理审计
 * <p>
 * 即治理操作的审计记录写入接口，用于记录负责人交接、成员移除、授权修改等操作，方便后续追溯。
 * 不在事务内调用远端日志服务；事件／审计失败使必要治理命令回滚。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface AuditSink {

    /**
     * 在当前事务内追加治理审计记录，写入失败时使事务回滚。
     */
    void append(AuditEntry entry);
}
