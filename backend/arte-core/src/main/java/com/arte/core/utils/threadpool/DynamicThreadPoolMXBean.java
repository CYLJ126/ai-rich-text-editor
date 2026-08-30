package com.arte.core.utils.threadpool;

/**
 * MBean 访问线程池的接口
 *
 * @author zhangsc
 * @since 2025/1/2 16:45
 */
public interface DynamicThreadPoolMXBean {
    int getCorePoolSize();

    void setCorePoolSize(int corePoolSize);

    int getMaximumPoolSize();

    void setMaximumPoolSize(int maximumPoolSize);

    void setKeepAliveTime(long keepAliveTime);

    long getKeepAliveTime();

    int getQueueSize();
}
