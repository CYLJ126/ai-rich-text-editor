package com.arte.core.utils;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.arte.core.constant.CoreConstant;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.utils.threadpool.DynamicThreadPool;
import com.arte.core.utils.threadpool.DynamicThreadPoolMBean;
import lombok.Getter;
import lombok.NonNull;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.management.NotCompliantMBeanException;
import java.util.concurrent.*;

/**
 * 线程池工具
 * 1. businessThreadPool：业务线程池，建议使用形如{@link CompletableFuture#runAsync(Runnable, Executor)}的方式调用；
 * 2. fixedThreadPool：固定线程池，用在长时间任务处理的地方，如异步下载等，一般不需要返回 Future；注意，每次增加调用时，需要重新衡量线程池大小；
 *
 * @author zhangsc
 * @since 2025/1/2 17:06
 */
@Service
public class ThreadPoolUtil implements EnvironmentAware, InitializingBean {

    private static final ThreadFactory BUSINESS_TREAD_FACTORY = new ThreadFactoryBuilder().setNamePrefix("business-pool-").build();
    private static final ThreadFactory FIXED_TREAD_FACTORY = new ThreadFactoryBuilder().setNamePrefix("fixed-pool-").build();

    /**
     * 业务线程池
     */
    @Getter
    private DynamicThreadPool businessThreadPool;
    /**
     * 固定线程池，用于任务
     * 一直占用的线程数大小需要增加1，即每增加一处调用，默认大小需要增加1
     */
    private DynamicThreadPool fixedThreadPool;

    private Environment environment;

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() throws NotCompliantMBeanException {
        // TODO 让 DynamicThreadPool 继承 ThreadPoolExecutor 或 ExecutorService
        this.businessThreadPool = new DynamicThreadPool(
                Integer.parseInt(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.business.coreSize"), "10")),
                Integer.parseInt(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.business.maxSize"), "50")),
                Long.parseLong(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.business.keepAliveTime"), "300")),
                TimeUnit.MICROSECONDS,
                new LinkedBlockingQueue<>(),
                BUSINESS_TREAD_FACTORY,
                new ThreadPoolExecutor.AbortPolicy());
        DynamicThreadPoolMBean mBeanDynamic = new DynamicThreadPoolMBean(businessThreadPool, "businessThreadPool");
        this.fixedThreadPool = new DynamicThreadPool(
                Integer.parseInt(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.fixed.coreSize"), "10")),
                Integer.parseInt(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.fixed.maxSize"), "10")),
                Long.parseLong(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.fixed.keepAliveTime"), "1800")),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Integer.parseInt(CharSequenceUtil.blankToDefault(environment.getProperty("threadPool.fixed.queueSize"), "1024"))),
                FIXED_TREAD_FACTORY,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        DynamicThreadPoolMBean mBeanFixed = new DynamicThreadPoolMBean(fixedThreadPool, "fixedThreadPool");
    }

    /**
     * 固定线程池调用入口
     * 根据该方法被调用处，计数应该设置多少线程
     * TODO 设置日志ID、用户信息
     *
     * @param runnable 异步任务
     */
    public void runFixedAsync(Runnable runnable) {
        UserOnlineInfo userOnlineInfo = UserContext.getUserOnlineInfo();
        String logId = MDC.get(CoreConstant.LOG_TRACE_ID);
        fixedThreadPool.execute(runnable);
    }

    /**
     * 业务线程池调用入口
     * TODO 设置日志ID、用户信息
     *
     * @param runnable 异步任务
     */
    public void runBusinessAsync(Runnable runnable) {
        businessThreadPool.execute(runnable);
    }

}
