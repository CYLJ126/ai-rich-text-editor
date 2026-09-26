package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * HTTP 或 OpenAPI 工具目录加载端口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@FunctionalInterface
public interface RemoteToolLoader {

    CompletionStage<List<Tool<?, ?>>> load(String endpoint, Map<String, Object> configuration);
}
