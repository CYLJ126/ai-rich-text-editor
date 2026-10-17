package com.arte.ainew.spi.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.search.SearchSync.*;

import java.time.Duration;
import java.util.List;

/**
 * 会话搜索后台同步状态的持久化接口
 * <p>
 * 维护代际租约、扫描检查点、待处理任务、同步版本及确认进度。
 * 通过独立短事务及租约版本校验，防止失去租约的任务继续更新同步状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchSyncStore {

    /**
     * 数据库 Unix 毫秒；扫描到期与租约统一使用此时钟，不和应用主机时钟混用。
     */
    long now();

    void register(String generation, String rulesVersion);

    Lease acquire(String generation, String worker, Duration duration);

    void renew(Lease lease, Duration duration);

    void release(Lease lease);

    Checkpoint checkpoint(Lease lease, String lane);

    void discovered(Lease lease, String lane, List<Unit> units, String cursor, boolean end, Duration pause);

    List<Work> known(Lease lease, String after, int size);

    List<Work> pending(Lease lease, int size);

    List<Entry> entries(Lease lease, Unit unit);

    Entry stage(Lease lease, Work work, Scope scope, String identity, String fingerprint, Desired desired);

    void acknowledge(Lease lease, Entry entry);

    void observe(Lease lease, Work work, Scope scope, Disposition disposition);

    long prepareActivation(Lease lease);

    void consumeActivation(Lease lease, long epoch);

    void complete(Lease lease, Work work, Scope scope);

    void fail(Lease lease, Work work, String errorCode, Duration delay);

    void continueWork(Lease lease, Work work, Duration delay);

    void reconcileDiscovered(Lease lease, List<Work> work, String cursor, boolean end, Duration pause);


    Status status(String generation);

    OwnerStatus status(String generation, ExecutionOwner owner);
}
