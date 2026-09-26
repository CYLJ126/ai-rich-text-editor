package com.arte.ai.service.tool.cluster;

import com.arte.ai.api.tool.ToolProviderManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 工具集群定时对账，补偿节点离线期间遗漏的 Redis Pub/Sub 事件。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "arte.ai.tool.cluster", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ToolClusterReconciler {

    private final ToolProviderManager providerManager;

    @Scheduled(initialDelayString = "${arte.ai.tool.cluster.reconcile-initial-delay-ms:60000}",
            fixedDelayString = "${arte.ai.tool.cluster.reconcile-interval-ms:60000}")
    public void reconcile() {
        providerManager.reconcileAllLocal().whenComplete((results, throwable) -> {
            if (throwable != null) {
                log.error("Periodic tool registry reconciliation failed", throwable);
            }
        });
    }
}
