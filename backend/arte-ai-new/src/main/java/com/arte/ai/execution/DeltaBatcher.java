package com.arte.ai.execution;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 在模型线程合并增量；首批立即可见，之后最多每 100ms 或 512 个码点提交一次。
 */
public final class DeltaBatcher implements Consumer<String> {
    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();
    private long lastFlush;

    public DeltaBatcher(Consumer<String> sink) {
        this.sink = sink;
    }

    public void accept(String text) {
        int offset = 0;
        while (offset < text.length()) {
            int end = text.offsetByCodePoints(offset, Math.min(512, text.codePointCount(offset, text.length())));
            pending.append(text, offset, end);
            offset = end;
            if (pending.length() >= 512 || lastFlush == 0 || System.nanoTime() - lastFlush >= Duration.ofMillis(100).toNanos())
                flush();
        }
    }

    public void flush() {
        if (pending.isEmpty()) return;
        sink.accept(pending.toString());
        pending.setLength(0);
        lastFlush = System.nanoTime();
    }
}
