package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Duration;

/**
 * Chat Completions SSE 交换的协议限制；供请求编码和响应帧解析使用，与 HTTP 连接池资源及出口策略分开。
 * <p>
 * 正式模型装配保持 64 KiB 帧和 1 MiB 请求的固定上限，测试可显式构造更小上限验证拒绝行为。
 *
 * @param maxFrameBytes               单个 SSE 数据帧的最大 UTF-8 字节数
 * @param maxRequestBytes             序列化后单次请求体的最大字节数
 * @param completedStreamDrainTimeout 收到结束标记后等待 HTTP 响应尾部排空的最长时间
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 14:25 ✾
 */
public record ChatCompletionsSseOptions(
        int maxFrameBytes,
        int maxRequestBytes,
        Duration completedStreamDrainTimeout) {

    public ChatCompletionsSseOptions {
        ContractChecks.range(maxFrameBytes, "maxFrameBytes", 1024, 1_048_576);
        ContractChecks.range(maxRequestBytes, "maxRequestBytes", 1024, 4_194_304);
        completedStreamDrainTimeout = completedStreamDrainTimeout == null ? Duration.ofMillis(100) : completedStreamDrainTimeout;
        ContractChecks.require(completedStreamDrainTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && completedStreamDrainTimeout.compareTo(Duration.ofSeconds(1)) <= 0, "Invalid completed stream drain timeout");
    }

    /**
     * 正式模型交换的固定大小上限与部署侧排空期限。
     */
    public static ChatCompletionsSseOptions defaults(Duration drainTimeout) {
        return new ChatCompletionsSseOptions(65_536, 1_048_576, drainTimeout);
    }
}
