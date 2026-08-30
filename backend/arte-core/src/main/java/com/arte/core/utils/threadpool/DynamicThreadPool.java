package com.arte.core.utils.threadpool;

import lombok.Getter;

import java.util.concurrent.*;

/**
 * 定义一个线程池
 * 只是把传统的线程池对象 ThreadPoolExecutor 封装了一下，并且提供了两个方法 setCorePoolSize 和 setMaximumPoolSize，通过这两个方法可以动态设置线程池的线程数。
 *
 * @author zhangsc
 * @since 2024/12/17 9:17
 */
@Getter
public class DynamicThreadPool {

    private final ThreadPoolExecutor threadPoolExecutor;

    public DynamicThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                             BlockingQueue<Runnable> workQueue, ThreadFactory businessTreadFactory,
                             RejectedExecutionHandler rejectedExecutionHandler) {
        threadPoolExecutor = new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, businessTreadFactory, rejectedExecutionHandler);
    }

    public int getCorePoolSize() {
        return threadPoolExecutor.getCorePoolSize();
    }

    public int getMaximumPoolSize() {
        return threadPoolExecutor.getMaximumPoolSize();
    }

    public void setCorePoolSize(int corePoolSize) {
        threadPoolExecutor.setCorePoolSize(corePoolSize);
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        threadPoolExecutor.setMaximumPoolSize(maximumPoolSize);
    }

    public void execute(Runnable command) {
        threadPoolExecutor.execute(command);
    }
}
