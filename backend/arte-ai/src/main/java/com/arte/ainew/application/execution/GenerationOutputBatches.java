package com.arte.ainew.application.execution;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationSignal;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首个文本立即提交，其余合批耐久写入。模型接收与慢 JDBC 之间使用有界队列，
 * 因此实时预览不受每批数据库往返限制；超限取消本次流并报告未知结果，不丢掉输出后假报成功。
 * 队列和累计正文均有限制，取消会传播给唯一一次模型订阅。
 */
public final class GenerationOutputBatches {
    public static final int MAX_SIGNALS = 128;
    public static final Duration MAX_WAIT = Duration.ofMillis(500);
    public static final int MAX_PENDING_SIGNALS = 8192;

    private GenerationOutputBatches() {
    }

    public static Flux<List<GenerationSignal>> batch(Flux<GenerationSignal> source) {
        return Flux.defer(() -> {
            var firstText = new AtomicBoolean(true);
            var textChars = new AtomicInteger();
            return source.doOnNext(signal -> {
                        if (signal instanceof GenerationSignal.Delta(GenerationEvent.TextDelta delta)
                                && textChars.addAndGet(delta.text().length()) > ContractChecks.MAX_TEXT_CHARS) {
                            throw GenerationException.output("OUTPUT_LIMIT_EXCEEDED");
                        }
                    })
                    .onBackpressureBuffer(MAX_PENDING_SIGNALS)
                    .onErrorMap(Exceptions::isOverflow, ignored -> GenerationException.output("OUTPUT_BUFFER_EXCEEDED"))
                    .windowUntil(signal -> signal instanceof GenerationSignal.Delta(GenerationEvent.TextDelta ignored)
                            && firstText.compareAndSet(true, false), false, 1)
                    .concatMap(window -> window.bufferTimeout(MAX_SIGNALS, MAX_WAIT, true).filter(batch -> !batch.isEmpty()), 1);
        });
    }
}
