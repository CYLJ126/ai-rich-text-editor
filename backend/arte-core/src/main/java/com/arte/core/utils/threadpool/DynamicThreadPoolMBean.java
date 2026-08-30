package com.arte.core.utils.threadpool;

import lombok.extern.slf4j.Slf4j;

import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * 动态线程池 MBean
 *
 * @author zhangsc
 * @since 2024/12/17 9:21
 */
@Slf4j
public class DynamicThreadPoolMBean extends StandardMBean implements DynamicThreadPoolMXBean {

    private final DynamicThreadPool dynamicThreadPool;
    private final String name;

    public DynamicThreadPoolMBean(DynamicThreadPool dynamicThreadPool, String name) throws NotCompliantMBeanException {
        // MBean 暴露出去的管理接口
        super(DynamicThreadPoolMXBean.class);
        this.dynamicThreadPool = dynamicThreadPool;
        this.name = name;
        registerMBean();
    }

    private void registerMBean() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(String.format("com.arte:type=%s", name));
            mbs.registerMBean(this, objectName);
        } catch (Exception e) {
            log.error("注册 MBean 时出错，忽略", e);
        }
    }

    @Override
    public int getCorePoolSize() {
        return dynamicThreadPool.getCorePoolSize();
    }

    @Override
    public void setCorePoolSize(int corePoolSize) {
        dynamicThreadPool.setCorePoolSize(corePoolSize);
    }

    @Override
    public int getMaximumPoolSize() {
        return dynamicThreadPool.getMaximumPoolSize();
    }

    @Override
    public void setMaximumPoolSize(int maximumPoolSize) {
        dynamicThreadPool.setMaximumPoolSize(maximumPoolSize);
    }

    @Override
    public void setKeepAliveTime(long keepAliveTime) {
        dynamicThreadPool.getThreadPoolExecutor().setKeepAliveTime(keepAliveTime, TimeUnit.MILLISECONDS);
    }

    @Override
    public long getKeepAliveTime() {
        return dynamicThreadPool.getThreadPoolExecutor().getKeepAliveTime(TimeUnit.MILLISECONDS);
    }

    @Override
    public int getQueueSize() {
        return dynamicThreadPool.getThreadPoolExecutor().getQueue().size();
    }
}
