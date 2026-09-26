package com.arte.ai.api.tool;

/**
 * 跨框架的取消信号。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolCancellation {

    boolean isCancellationRequested();

    void onCancellation(Runnable callback);
}
