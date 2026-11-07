package com.arte.ainew.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新 AI 共用 HTTP 资源参数的部署配置入口；三个参数组独立取值，连接池及出口授权仍分别装配。
 * <p>
 * 本类只绑定本机传输资源，不开启模型或工具功能，不创建连接，也不从数据库读取业务配置。
 *
 * @param model    模型请求运行时的资源参数，默认 32 个池、每池 8 个连接、32 个等待请求、5 秒获取超时及 64 KiB 缓冲
 * @param toolHttp HTTP 工具运行时的资源参数，默认 8 个池、每池 4 个连接、8 个等待请求、2 秒获取超时及 64 KiB 缓冲
 * @param toolMcp  MCP 工具运行时的资源参数，默认值与 HTTP 工具一致，配置及运行实例独立
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 14:25 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-http", ignoreUnknownFields = false)
public record NewAiHttpProperties(HttpRuntimeOptions model, HttpRuntimeOptions toolHttp, HttpRuntimeOptions toolMcp) {

    public NewAiHttpProperties {
        model = HttpRuntimeOptions.resolve(model, HttpRuntimeOptions.modelDefaults());
        toolHttp = HttpRuntimeOptions.resolve(toolHttp, HttpRuntimeOptions.toolDefaults());
        toolMcp = HttpRuntimeOptions.resolve(toolMcp, HttpRuntimeOptions.toolDefaults());
    }
}
