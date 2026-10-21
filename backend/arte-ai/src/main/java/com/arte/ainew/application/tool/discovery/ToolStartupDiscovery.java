package com.arte.ainew.application.tool.discovery;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;

/**
 * 显式启用后在 Worker 启动前安装健康静态工具清单；总等待有界，只登记部署事实，不发布、建绑或授权。
 * 发现失败即终止本次启动，避免启用 TOOL 的节点在无可用实现时继续对外接收新调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
public final class ToolStartupDiscovery implements SmartLifecycle {

    private final StaticToolDiscovery discovery;
    private volatile boolean running;

    public ToolStartupDiscovery(StaticToolDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        var result = discovery.refresh().block(Duration.ofSeconds(30));
        if (result == null || !result.state().discoveryHealthy())
            throw new IllegalStateException("Local TOOL startup discovery failed: " + (result == null ? "EMPTY" : result.code()));
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
