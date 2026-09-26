package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolProviderSyncResult;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 工具提供者管理入口，负责启停和同步运行时工具目录。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public interface ToolProviderManager {

    CompletionStage<ToolProviderSyncResult> synchronize(String providerId);

    CompletionStage<List<ToolProviderSyncResult>> synchronizeAll();

    /**
     * 根据共享数据库目录重建当前节点的提供者运行时，不修改目录、不再次广播集群事件。
     */
    CompletionStage<ToolProviderSyncResult> reconcileLocal(String providerId);

    /**
     * 对当前节点可自动刷新的全部提供者执行运行时对账。
     */
    CompletionStage<List<ToolProviderSyncResult>> reconcileAllLocal();

    void enable(String providerId);

    void disable(String providerId, String reason);
}
