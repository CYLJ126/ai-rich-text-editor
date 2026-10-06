package com.arte.ainew.infrastructure.http;

/**
 * 实例内协议帧
 * <p>
 * SseFrame 不是耐久平台事件；JSON 业务含义由供应商适配器解释。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public sealed interface SseFrame permits SseFrame.Data, SseFrame.Done {

    record Data(String json) implements SseFrame {
    }

    record Done() implements SseFrame {
    }
}
