package com.arte.core.application.workspace;

import java.io.Serial;

/**
 * 组合调用违反事务级治理锁顺序；属于调用方式错误，事务已被标记为必须回滚。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 11:50 ✾
 */
public final class GovernanceLockOrderException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = -916660814196979782L;

    public GovernanceLockOrderException() {
        super("Governance locks must acquire all subjects before tenants, with new identifiers in ascending order");
    }
}
