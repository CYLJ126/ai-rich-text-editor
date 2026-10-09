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
     * 最终写入／受理协作拒绝在事务外调用，不偷偷另开事务冒充原子提交。
     */
    default void requireActive() {
        if (!isTransactionActive()) throw new IllegalStateException("Active local transaction required");
    }
}
