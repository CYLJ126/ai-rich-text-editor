package com.arte.core.spi.platform;

import java.util.function.Supplier;

/**
 * 同线程、同 DataSource 的本地事务
 * <p>
 * required 加入当前事务；任何失败整体回滚，不切线程、不等待外部网络。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PublicTransactionRunner {

    /**
     * 加入当前本地事务，缺少事务时创建事务，执行 work 并返回其结果。
     */
    <T> T required(Supplier<T> work);

    /**
     * 判断当前线程是否存在可参与的本地事务。
     */
    boolean isTransactionActive();

    /**
     * 按 key 取得或创建绑定到实际外层事务的协作状态；同一事务的所有 required 嵌套和服务实例共享该状态。
     * 状态须随事务完成清理，挂起／恢复时跟随原事务，不能仅绑定线程或单次 required 调用。
     * 调用方须处于同线程的有效事务；不得默认另建状态冒充共享事务状态。
     */
    <T> T resource(Object key, Supplier<T> factory);

    /**
     * 将当前实际事务标记为必须回滚；即使调用方捕获异常，外层也不能提交。
     */
    void markRollbackOnly();

    /**
     * 最终写入／受理协作拒绝在事务外调用，不偷偷另开事务冒充原子提交。
     */
    default void requireActive() {
        if (!isTransactionActive()) throw new IllegalStateException("Active local transaction required");
    }
}
