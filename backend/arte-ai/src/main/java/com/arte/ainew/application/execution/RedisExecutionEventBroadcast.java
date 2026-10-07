package com.arte.ainew.application.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiEventProperties;
import com.arte.ainew.pojo.execution.OutboxMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.BaseStatusListener;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 所有实例（包括 worker-disabled 的 HTTP 节点）均订阅。耐久事件只传 owner/id/sequence 指针；
 * live-text-v2 另传有界模型文字预览、偏移与发送节点标识，忽略本节点回送；兼容接收 v1。
 * Pub/Sub 丢失的提示／预览在重新订阅时通过耐久重放恢复；
 * 发布失败保留 EVENT Outbox，统一恢复扫描重试。停机只移除本组件的监听器，不关闭共享客户端。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 00:06 ✾
 */
@Slf4j
public final class RedisExecutionEventBroadcast implements ExecutionEventBroadcast, SmartLifecycle {

    /**
     * 通知 JSON 的长度上限，按 String.length() 的 UTF-16 字符单元计数；发送与接收均检查，不是字节数上限。
     */
    private static final int MAX_SIGNAL_CHARS = 8192;
    private static final String LEGACY_TEXT_PREFIX = "live-text-v1:";
    private static final String TEXT_PREFIX = "live-text-v2:";
    private final String senderId = java.util.UUID.randomUUID().toString();
    private static final int MAX_TEXT_SIGNAL_CHARS = 16384;
    private final LiveTextNotifier liveTextNotifier;

    public record TextSignal(String senderId, LiveTextDelta delta) {
        public TextSignal {
            ContractChecks.id(senderId, "senderId");
            Objects.requireNonNull(delta, "delta");
        }
    }

    /**
     * 使用配置通道名称和 StringCodec 创建的 Redis Topic，负责发布通知及管理本组件的两类监听器。
     */
    private final RTopic redisTopic;

    /**
     * 本节点的唤醒通道：将 Redis 通知交给匹配的本地订阅者，由后续订阅流程从数据库重放事件。
     */
    private final LocalExecutionEventNotifier localExecutionEventNotifier;

    /**
     * 通知配置，当前组件使用其中的 Redis 通道名称及单次发布超时。
     */
    private final NewAiEventProperties properties;

    /**
     * 本组件独立的 JSON 编解码器，负责 Signal 与 JSON 字符串的转换，不受应用全局编解码配置影响。
     */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 本组件已登记的监听器 ID，用于失败或停机时定向清理；集合访问由 synchronized(this) 保护。
     */
    private final Set<Integer> listenerIds = new HashSet<>();

    /**
     * 当前启动批次的身份标识，每次 start 创建新的 Object，stop 时清空。
     * 异步回调使用对象身份比较（==）确认自己仍属于当前批次，防止旧批次在重启后登记监听器或处理通知。
     * 这是进程内生命周期标识，不是认证 Token；volatile 保证异步线程能看到更新。
     */
    private volatile Object generation;

    /**
     * 生命周期是否处于启动状态，包含正在注册或重试的阶段；由 isRunning() 暴露，不表示 Redis 当前连接正常。
     */
    private volatile boolean running;

    /**
     * 本次启动的状态监听器与消息监听器是否都已成功注册，stop 时重置，由 Lombok 生成 isReady()。
     * Redis 临时断线时此值不会自动重置，表示注册流程已完成，不是实时连接健康状态。
     */
    @Getter
    private volatile boolean ready;

    /**
     * 注册监听器及失败重试流程的 Reactor 订阅句柄；dispose 停止该流程，已登记监听器仍需按 ID 单独移除。
     */
    private Disposable subscription;

    /**
     * Redis 跨实例广播的事件通知指针（即在 Redis 中发布的事件内容）
     * <p>
     * 由已提交事件对应的 EVENT Outbox 消息生成并序列化为 JSON。
     * 接收实例根据归属和调用 ID 唤醒本地订阅者，订阅者再按已处理游标从数据库重放事件。
     * 通知本身不持久保存，可能重复或在断线期间丢失；执行事实以数据库中已提交的事件为准。
     *
     * @param schemaVersion 通知 JSON 的协议结构版本，当前仅支持 1，用于校验发送端与接收端的格式兼容性。
     * @param owner         调用所属的租户、工作空间及主体，由可信执行上下文确定，用于匹配并隔离本地订阅者；
     *                      此字段不授予读取权限，实际事件重放仍须检查当前授权。
     * @param invocationId  已提交事件所属的平台调用 ID，用于定位该调用的订阅者及数据库事件记录。
     * @param sequence      对应数据库事件在该调用内的递增序号，从 1 开始；用于提示存在新事件及过滤重复或旧通知。
     *                      接收端从自身已处理游标继续重放，不直接跳到此序号，以保留中间尚未处理的事件。
     */
    public record Signal(int schemaVersion, ExecutionOwner owner, String invocationId, long sequence) {

        public Signal {
            ContractChecks.require(schemaVersion == 1, "Unsupported notification schema");
            Objects.requireNonNull(owner, "owner");
            ContractChecks.id(invocationId, "invocationId");
            ContractChecks.range(sequence, "sequence", 1, Long.MAX_VALUE);
        }
    }

    public RedisExecutionEventBroadcast(RedissonClient client,
                                        LocalExecutionEventNotifier localExecutionEventNotifier,
                                        NewAiEventProperties properties) {
        this(client, localExecutionEventNotifier, properties, null);
    }

    public RedisExecutionEventBroadcast(RedissonClient client, LocalExecutionEventNotifier localExecutionEventNotifier,
                                        NewAiEventProperties properties, LiveTextNotifier liveTextNotifier) {
        this.liveTextNotifier = liveTextNotifier;
        this.redisTopic = Objects.requireNonNull(client, "Redis transport requires RedissonClient")
                .getTopic(properties.channel(), StringCodec.INSTANCE);
        this.localExecutionEventNotifier = localExecutionEventNotifier;
        this.properties = properties;
    }

    @Override
    public Mono<Void> publish(OutboxMessage message) {
        return Mono.defer(() -> {
            String encoded = jsonMapper.writeValueAsString(new Signal(1, message.owner(), message.invocationId(), message.eventSequence()));
            ContractChecks.require(encoded.length() <= MAX_SIGNAL_CHARS, "Oversized notification");
            return Mono.fromCompletionStage(redisTopic.publishAsync(encoded)).timeout(properties.publishTimeout()).then();
        });
    }

    @Override
    public Mono<Void> publishText(LiveTextDelta delta) {
        return Mono.defer(() -> {
            String encoded = TEXT_PREFIX + jsonMapper.writeValueAsString(new TextSignal(senderId, delta));
            ContractChecks.require(encoded.length() <= MAX_TEXT_SIGNAL_CHARS, "Oversized text preview");
            return Mono.fromCompletionStage(redisTopic.publishAsync(encoded)).timeout(properties.publishTimeout()).then();
        });
    }

    /**
     * 处理 Redis 消息监听器收到的通知 JSON：先检查长度，再反序列化为 Signal 并通过其构造器校验字段。
     * 校验成功后，按 owner、invocationId 和 sequence 唤醒本地订阅者；实际事件读取与序号去重由后续订阅流程完成。
     * 此方法不直接查询数据库或写入 SSE。解析、校验或分发发生运行时异常时，忽略本次通知并记录不含消息正文的警告。
     *
     * @param encoded Redis Topic 使用 StringCodec 解码得到的通知 JSON 字符串，即 publish 序列化后的 Signal。
     */
    private void receive(String encoded) {
        try {
            if (encoded.startsWith(TEXT_PREFIX)) {
                if (encoded.length() > MAX_TEXT_SIGNAL_CHARS)
                    throw new IllegalArgumentException("Oversized text preview");
                var signal = jsonMapper.readValue(encoded.substring(TEXT_PREFIX.length()), TextSignal.class);
                if (liveTextNotifier != null && !senderId.equals(signal.senderId()))
                    liveTextNotifier.receive(signal.delta());
                return;
            }
            if (encoded.startsWith(LEGACY_TEXT_PREFIX)) {
                if (encoded.length() > MAX_TEXT_SIGNAL_CHARS)
                    throw new IllegalArgumentException("Oversized text preview");
                var delta = jsonMapper.readValue(encoded.substring(LEGACY_TEXT_PREFIX.length()), LiveTextDelta.class);
                if (liveTextNotifier != null) liveTextNotifier.receive(delta);
                return;
            }
            if (encoded.length() > MAX_SIGNAL_CHARS) {
                throw new IllegalArgumentException("Oversized notification");
            }
            var signal = jsonMapper.readValue(encoded, Signal.class);
            localExecutionEventNotifier.publish(signal.owner(), signal.invocationId(), signal.sequence());
        } catch (RuntimeException error) {
            log.warn("Ignored invalid AI Redis notification, type={}", error.getClass().getName());
        }
    }

    /**
     * 接管 addListenerAsync 已发起的异步注册结果，将其包装为 Mono，并在注册成功时管理返回的监听器 ID。
     * 用法为 {@code register(redisTopic.addListenerAsync(...), token)}，状态监听器和消息监听器均复用此处理。
     * <p>
     * 若组件仍在运行且 token 属于当前启动批次，将 ID 加入 listenerIds，供停机清理；
     * 若已经停机或属于旧批次，则立即发起移除，避免异步注册完成后遗留监听器。
     * Mono.fromFuture 的第二个参数 true 表示取消 Reactor 订阅时不取消底层 Future，
     * 使迟到的注册结果仍能执行清理回调；注册异常会沿 Mono 传播，由 start 的重试流程处理。
     *
     * @param stage addListenerAsync 返回的异步结果，成功值为 Redisson 分配的监听器 ID；注册操作已由调用方发起。
     * @param token 发起注册时 start 创建的启动批次标识，通过与 generation 的对象身份比较识别过期注册。
     * @return 输出注册返回 ID 的 Mono；当前批次的 ID 已登记，过期批次的 ID 已发起移除，失败时传播注册异常。
     */
    private Mono<Integer> register(CompletionStage<Integer> stage, Object token) {
        return Mono.fromFuture(stage.thenApply(id -> {
            synchronized (this) {
                if (running && generation == token) {
                    // 监听器注册好后，将 ID 加入 listenerIds，供停机清理
                    listenerIds.add(id);
                } else {
                    // 若已经停机或属于旧批次，则立即发起移除，避免异步注册完成后遗留监听器
                    remove(id);
                }
            }
            return id;
        }).toCompletableFuture(), true);
    }

    /**
     * 按 ID 异步注销本组件的监听器，用于停机、注册失败或旧启动批次的迟到结果清理；空参数列表直接返回。
     * 方法发起请求后即返回，不等待 Redis 确认；移除失败只记录警告，不关闭共享 RedissonClient。
     * listenerIds 集合的更新由调用方负责，此方法仅向 Topic 发起注销请求。
     *
     * @param ids 待注销的监听器 ID，来自 addListenerAsync 的成功结果；仅传入本组件拥有的 ID，避免影响其他监听器。
     */
    private void remove(Integer... ids) {
        if (ids.length == 0) {
            return;
        }
        redisTopic.removeListenerAsync(ids).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("AI Redis listener cleanup failed, listenerCount={}, type={}", ids.length, error.getClass().getName());
            }
        });
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Object token = new Object();
        generation = token;
        subscription = Mono.defer(() -> {
                    if (!running || generation != token) {
                        return Mono.empty();
                    }
                    // 订阅状态监听器，触发时机：Redis 通道订阅成功，包括断线后重新订阅
                    // 调用 wakeSubscribers()，让本节点所有订阅者从数据库检查遗漏事件
                    return register(redisTopic.addListenerAsync(new BaseStatusListener() {
                        @Override
                        public void onSubscribe(String channel) {
                            if (running && generation == token) {
                                log.info("AI Redis channel subscribed; replaying missed events");
                                localExecutionEventNotifier.wakeSubscribers();
                            }
                        }
                    }), token).flatMap(statusId -> {
                        if (!running || generation != token) {
                            return Mono.empty();
                        }
                        // 订阅 Signal 类型消息，触发时机：收到通道中发布的 Signal 消息
                        // 调用 receive(signal)，按归属和调用 ID 唤醒对应订阅者
                        return register(redisTopic.addListenerAsync(String.class, (channel, signal) -> {
                            if (running && generation == token) {
                                receive(signal);
                            }
                        }), token).doOnError(error -> {
                            synchronized (this) {
                                listenerIds.remove(statusId);
                            }
                            remove(statusId);
                        });
                    });
                }).retryWhen(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .doBeforeRetry(retry -> log.warn("AI Redis subscription failed; retrying, retryNumber={}, type={}",
                                retry.totalRetries() + 1, retry.failure().getClass().getName())))
                .subscribe(ignored -> {
                    if (running && generation == token) {
                        ready = true;
                        log.info("AI Redis event and text notification listeners ready");
                        // 两个 Redis 监听器都注册成功后，让本节点现有的事件订阅者再检查一次数据库，补回注册期间可能遗漏的事件
                        localExecutionEventNotifier.wakeSubscribers();
                    }
                }, error -> log.warn("AI Redis subscription stopped, type={}", error.getClass().getName()));
    }

    @Override
    public synchronized void stop() {
        if (running) log.info("AI Redis notification listeners stopping, listenerCount={}", listenerIds.size());
        running = false;
        ready = false;
        generation = null;
        if (subscription != null) {
            subscription.dispose();
        }
        var ids = listenerIds.toArray(Integer[]::new);
        listenerIds.clear();
        remove(ids);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
