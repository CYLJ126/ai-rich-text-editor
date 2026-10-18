package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDiscoveryReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import reactor.core.publisher.Mono;

/**
 * 本地完整刷新管理事务；资格、目录登记和原始命令／审计共用物理事务，不持锁等来源读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public interface ToolDiscoveryManagementStore {

    record Commit(StoreOutcome<ToolCatalogStore.Batch> catalog, StoreOutcome<ToolDiscoveryReceipt> receipt) {
    }

    /**
     * 当前授权后查原回执；未处理为空，同键变更语义为冲突。
     */
    Mono<StoreOutcome<ToolDiscoveryReceipt>> find(TrustedActor actor, Scope scope, String key, String reason, Runnable authorization);

    /**
     * 同库提交整份目录与原始审计；同键竞争时不提交另一份新候选。
     */
    Mono<Commit> commit(TrustedActor actor, Scope scope, String key, String reason, ToolCatalogStore.Batch batch, Runnable authorization);

    /**
     * 来源失败没有目录事实可提交，仍按原键记录稳定决定；数据库故障不调用本方法。
     */
    Mono<StoreOutcome<ToolDiscoveryReceipt>> failure(TrustedActor actor, Scope scope, String key, String reason,
                                                     ToolDiscoveryReceipt.Code code, Runnable authorization);
}
