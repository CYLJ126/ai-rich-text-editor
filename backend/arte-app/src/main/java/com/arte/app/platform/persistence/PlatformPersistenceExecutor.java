package com.arte.app.platform.persistence;

import com.arte.core.spi.platform.PublicTransactionRunner;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.function.Supplier;

/**
 * 平台持久化调用的响应式调度适配器
 * <p>
 * 将完整的同步数据库调用延迟到订阅后，通过宿主提供的调度器执行，避免在请求处理线程直接执行阻塞 JDBC。
 * 需要事务时，将整个同步回调及其本地事务一起调度，不能把事务内的各个 Store 调用分别切换线程。
 * 回调须在当前工作线程同步完成，不应返回尚未执行的 Publisher、另起异步任务或等待外部网络。
 * <p>
 * 本类只负责调度及事务协作，身份、授权和幂等由调用方或应用服务处理；不缓存结果、不自动重试。
 * 取消订阅不能保证撤销已经开始的 JDBC 操作或提交，写入结果不确定时仍需按原幂等键核对。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class PlatformPersistenceExecutor {

    private final Scheduler scheduler;
    private final PublicTransactionRunner transactions;

    /**
     * 使用宿主的数据库调度器及同库本地事务执行器构造适配器。
     *
     * @param scheduler    同步数据库调用使用的调度器，生产环境由宿主配置有界线程及排队容量
     * @param transactions 负责在工作线程中创建或加入本地事务的执行器
     */
    public PlatformPersistenceExecutor(Scheduler scheduler, PublicTransactionRunner transactions) {
        this.scheduler = scheduler;
        this.transactions = transactions;
    }

    /**
     * 将一次完整同步调用延迟到订阅后，在指定数据库调度器上执行。
     * <p>
     * 此方法仅提供调度，不自动创建事务或开启只读数据库快照；回调中的应用服务可以自行管理事务。
     * 每次订阅都可能重新执行回调，业务写入不能依赖 Mono 对象实现防重。
     *
     * @param work 在工作线程同步完成的本地数据库调用
     * @param <T>  回调结果类型
     * @return 回调结果的响应式流；返回 null 时空完成，回调异常通过错误信号传播
     */
    public <T> Mono<T> read(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(scheduler);
    }

    /**
     * 在数据库工作线程的同一本地事务内执行完整同步回调。
     * <p>
     * 通过 read 调度后调用 required：加入该工作线程当前可参与的事务，缺少事务时创建新事务，
     * 回调结束后按事务执行器规则提交或回滚。不会将请求线程已有的线程绑定事务自动传播到工作线程。
     * 回调必须在返回前完成全部本地操作，不能在返回 Publisher 后期待其后续执行仍属于此事务。
     *
     * @param work 在同一工作线程及本地事务内同步完成的业务数据库操作
     * @param <T>  回调结果类型
     * @return 事务调用的响应式结果；返回 null 时仍正常结束事务并空完成，失败按事务规则回滚并传播错误
     */
    public <T> Mono<T> inTransaction(Supplier<T> work) {
        return read(() -> transactions.required(work));
    }
}
