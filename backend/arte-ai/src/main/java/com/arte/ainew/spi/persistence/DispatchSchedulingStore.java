package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.OutboxMessage;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 数据库在领取前仲裁分类／空间／提供者并发，按空间游标公平领取；没有额度时不先占领取租约。
 * 过期队列收尾独立于执行槽位，避免所有槽位繁忙时无法结束尚未派发的调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
public interface DispatchSchedulingStore {

    Mono<List<OutboxMessage>> claimClass(OutboxMessage.WorkClass type, String workerId, Duration lease, int limit, String nodeManifest);

    Mono<Integer> expireQueued(int limit);
}
