package com.arte.ainew.application.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.OutboxMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前节点的内存唤醒通道，分别连接执行事件订阅者与 EVENT Outbox 发布器。
 * <p>
 * 执行事件订阅者接收事件序号提示，按自身游标从数据库重放；发布器接收领取提示，查询并处理待发布的 Outbox。
 * 提示值 0L 表示主动检查，不重置游标；正数表示某个已提交事件的序号，由订阅服务过滤已处理的旧提示。
 * 本类不持久保存提示，也不直接执行数据库查询、SSE 写入或模型调用，执行事实以数据库中已提交的记录为准。
 * <p>
 * 两类提示流均使用 LATEST 策略，在下游消费不足时保留最新待处理提示；被合并的提示对应的数据仍由数据库读取。
 * 每次订阅先登记发送入口，再发初始提示，避免先读取数据库、后登记监听时遗漏唤醒；订阅结束或取消时自动注销。
 * 单实例可直接分发已提交事件指针，多实例则由 Redis 广播组件收到指针后调用本通道唤醒当前节点的订阅者。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
public final class LocalExecutionEventNotifier {

    /**
     * 一条本地执行事件订阅的路由信息与数据发送入口，通常对应一条 SSE 执行事件订阅。
     * 相同归属、相同调用可有多个订阅，各自拥有独立的 sink 和下游处理游标。
     *
     * @param owner        调用所属的租户、工作空间和主体，用于精确匹配通知；当前读取权限由执行事件服务检查。
     * @param invocationId 订阅关注的平台调用 ID，用于将该调用的事件提示送到对应订阅者。
     * @param sink         本次订阅的 Long 提示发送入口；next 发送提示，onDispose 注册订阅注销回调。
     */
    private record Listener(ExecutionOwner owner, String invocationId, FluxSink<Long> sink) {
    }

    /**
     * 当前节点已登记的执行事件订阅，供按归属和调用 ID 分发或统一唤醒；集合操作由 synchronized(listeners) 保护。
     */
    private final Set<Listener> listeners = new HashSet<>();

    /**
     * 通过 wakeups() 登记的 EVENT Outbox 发布器提示发送入口，与执行事件订阅的 listeners 分开管理。
     * 用于事务提交后或满批处理后的再次领取；集合操作由 synchronized(publishers) 保护。
     */
    private final Set<FluxSink<Long>> publishers = new HashSet<>();

    /**
     * 为指定归属和调用创建提示流，由执行事件服务检查权限及调用归属后订阅。
     * 每次实际订阅时创建并登记独立 Listener，随后发送 0L，触发从下游当前游标读取数据库。
     * 返回流尚未被订阅时不会登记 Listener；流结束或被取消后通过 onDispose 从集合中移除。
     *
     * @param owner        经调用方确认的调用归属，后续通知必须同时匹配该归属与调用 ID。
     * @param invocationId 本次订阅关注的平台调用 ID。
     * @return 每次订阅独立登记、使用 LATEST 策略的 Long 提示流；0L 为主动检查，正数为已提交事件序号。
     */
    public Flux<Long> watch(ExecutionOwner owner, String invocationId) {
        return Flux.create(sink -> {
            var listener = new Listener(owner, invocationId, sink);
            synchronized (listeners) {
                listeners.add(listener);
            }
            sink.onDispose(() -> {
                synchronized (listeners) {
                    listeners.remove(listener);
                }
            });
            sink.next(0L);
        }, FluxSink.OverflowStrategy.LATEST);
    }

    /**
     * 将已提交事件对应的 EVENT Outbox 指针分发给本节点匹配的执行事件订阅者，供本地通知传输调用。
     * 从消息提取归属、调用 ID 和事件序号，交给 publish(owner, invocationId, sequence)；Outbox 确认由发布器处理。
     *
     * @param message EVENT 类型的 Outbox 消息，指向数据库中已提交的事件。
     */
    public void publish(OutboxMessage message) {
        publish(message.owner(), message.invocationId(), message.eventSequence());
    }

    /**
     * 向归属和调用 ID 都匹配的所有本地订阅者发送事件序号提示，供本地传输及 Redis 消息接收流程调用。
     * 先在集合锁内复制订阅快照，再在锁外发送提示；无匹配订阅者时直接结束，后续新订阅仍从数据库恢复历史。
     * 本方法只分发提示；重复或旧序号由下游执行事件服务根据自身游标过滤。
     *
     * @param owner        已提交事件所属的租户、工作空间及主体。
     * @param invocationId 已提交事件所属的平台调用 ID。
     * @param sequence     数据库事件在该调用内的递增序号，从 1 开始；仅用于唤醒及旧提示过滤，不替换下游游标。
     */
    public void publish(ExecutionOwner owner, String invocationId, long sequence) {
        Listener[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(Listener[]::new);
        }
        for (var listener : snapshot) {
            if (listener.owner().equals(owner) && listener.invocationId().equals(invocationId)) {
                listener.sink().next(sequence);
            }
        }
    }

    /**
     * 给当前节点所有执行事件订阅者发送 0L 主动检查提示，供 Redis 首次完整注册或重新订阅成功时调用。
     * 让订阅者补读注册空窗或断线期间遗漏的事件，包括已经由发布端 ACK、不会再次领取的 Outbox 所对应的事件。
     * <ol>
     *     <li>给本节点每个订阅者发送一个 0L 唤醒提示。</li>
     *     <li>订阅者重新检查读取权限。</li>
     *     <li>从自己已经处理的事件序号继续读取数据库，有新事件就通过 SSE 通知前端。</li>
     * </ol>
     * 0L 是本项目约定的检查提示，不是数据库事件序号，也不会把已处理游标重置为 0。
     * 此方法唤醒执行事件订阅者；EVENT Outbox 发布器通过 wakePublisher() 单独唤醒。
     */
    public void wakeSubscribers() {
        Listener[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(Listener[]::new);
        }
        for (var listener : snapshot) {
            listener.sink().next(0L);
        }
    }

    /**
     * 创建 EVENT Outbox 发布器的领取提示流，供 ExecutionEventPublisher 合并事务提交唤醒与统一恢复扫描。
     * 每次实际订阅时登记对应 sink 并发送初始 0L，使发布器启动后立即检查待发布记录；结束或取消时注销该 sink。
     * 此流的提示只表示应执行一次领取检查，具体待处理记录从数据库查询，不携带调用 ID 或事件序号。
     *
     * @return 使用 LATEST 策略合并待处理提示的 Long 流，提示值为 0L，发布器不依赖该数值定位记录。
     */
    public Flux<Long> wakeups() {
        return Flux.create(sink -> {
            synchronized (publishers) {
                publishers.add(sink);
            }
            sink.onDispose(() -> {
                synchronized (publishers) {
                    publishers.remove(sink);
                }
            });
            sink.next(0L);
        }, FluxSink.OverflowStrategy.LATEST);
    }

    /**
     * 向已登记的 EVENT Outbox 发布器发送 0L 提示，使其异步检查并领取待发布记录。
     * 由存储事务的 afterCommit 回调在提交成功后调用，也由发布器处理满批记录后调用以继续领取下一批。
     * 本方法只发送提示，不执行 JDBC、SSE 写入或模型调用；已有发布器通过 publishOn 在工作调度器上处理领取。
     * 无发布器订阅时不保存提示，耐久 Outbox 由后续初始领取或统一恢复扫描处理。
     */
    public void wakePublisher() {
        List<FluxSink<Long>> snapshot;
        synchronized (publishers) {
            snapshot = List.copyOf(publishers);
        }
        for (var sink : snapshot) {
            sink.next(0L);
        }
    }
}
