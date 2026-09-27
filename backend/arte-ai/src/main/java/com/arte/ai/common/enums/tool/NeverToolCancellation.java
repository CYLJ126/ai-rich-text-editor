package com.arte.ai.common.enums.tool;

import com.arte.ai.api.tool.ToolCancellation;

/**
 * 默认的不取消信号。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public enum NeverToolCancellation implements ToolCancellation {
    INSTANCE;

    @Override
    public boolean isCancellationRequested() {
        return false;
    }

    @Override
    public void onCancellation(Runnable callback) {
        // 永不触发。
    }
}
