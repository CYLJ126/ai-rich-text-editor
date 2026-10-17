package com.arte.ainew.spi.search;

import java.util.concurrent.Callable;

/**
 * 会话搜索索引维护的互斥接口
 * <p>
 * 协调初始化与显式代际激活，避免并发修改同一组别名。
 * 互斥范围不包含业务写入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchIndexLock {

    /**
     * 获取互斥锁后执行维护操作并返回结果，结束时释放锁；获取失败或操作失败时抛出异常。
     */
    <T> T locked(Callable<T> action) throws Exception;
}
