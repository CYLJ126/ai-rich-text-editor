package com.arte.ainew.execution;

import com.arte.ainew.application.execution.GenerationOutputBatches;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationSignal;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GenerationOutputBatchesTest {
    private GenerationSignal delta(String text) {
        return new GenerationSignal.Delta(new GenerationEvent.TextDelta(text));
    }

    @Test
    public void firstTextFlushesImmediatelyWithoutCompletingProvider() {
        Sinks.Many<GenerationSignal> source = Sinks.many().unicast().onBackpressureBuffer();
        StepVerifier.create(GenerationOutputBatches.batch(source.asFlux()))
                .then(() -> source.tryEmitNext(delta("第一段")))
                .assertNext(batch -> assertEquals("第一段", ((GenerationEvent.TextDelta) ((GenerationSignal.Delta) batch.getFirst()).event()).text()))
                .thenCancel().verify(Duration.ofSeconds(2));
    }

    @Test
    public void sparseFollowingDeltaFlushesAtTimeLimit() {
        StepVerifier.withVirtualTime(() -> GenerationOutputBatches.batch(Flux.concat(
                        Flux.just(delta("首字")), Flux.just(delta("后续")).delayElements(Duration.ofMillis(20)), Flux.never())))
                .expectNextCount(1).thenAwait(GenerationOutputBatches.MAX_WAIT.plusMillis(20))
                .assertNext(batch -> assertEquals(1, batch.size())).thenCancel().verify(Duration.ofSeconds(2));
    }

    @Test
    public void countLimitAndSlowConsumerPreserveOrderWithOneSubscription() {
        var subscriptions = new AtomicInteger();
        var source = Flux.range(0, 200).map(i -> delta(i + ",")).doOnSubscribe(ignored -> subscriptions.incrementAndGet());
        var batches = GenerationOutputBatches.batch(source).delayElements(Duration.ofMillis(10)).collectList().block(Duration.ofSeconds(5));
        assertEquals(1, subscriptions.get());
        assertEquals(1, batches.getFirst().size());
        assertTrue(batches.stream().allMatch(batch -> batch.size() <= GenerationOutputBatches.MAX_SIGNALS));
        String result = batches.stream().flatMap(java.util.List::stream)
                .map(signal -> ((GenerationEvent.TextDelta) ((GenerationSignal.Delta) signal).event()).text()).reduce("", String::concat);
        String expected = java.util.stream.IntStream.range(0, 200).mapToObj(i -> i + ",").reduce("", String::concat);
        assertEquals(expected, result);
    }

    @Test
    public void providerContinuesStreamingWhileFirstDatabaseWriteIsBlocked() {
        var received = new AtomicInteger();
        var subscriptions = new AtomicInteger();
        var gate = Sinks.<Void>one();
        StepVerifier.withVirtualTime(() -> GenerationOutputBatches.batch(
                                Flux.range(0, 400).delayElements(Duration.ofMillis(5)).map(i -> delta(i + ","))
                                        .doOnSubscribe(ignored -> subscriptions.incrementAndGet())
                                        .doOnNext(ignored -> received.incrementAndGet()))
                        .concatMap(batch -> gate.asMono().thenReturn(batch), 1).collectList())
                .thenAwait(Duration.ofSeconds(3))
                .then(() -> {
                    // 原来的反压链路在这里仅能收到几个预取批次，无法持续向 SSE 发新字。
                    assertEquals(400, received.get());
                    gate.tryEmitEmpty();
                })
                .assertNext(batches -> {
                    assertEquals(1, batches.getFirst().size());
                    assertEquals(400, batches.stream().mapToInt(java.util.List::size).sum());
                    String actual = batches.stream().flatMap(java.util.List::stream)
                            .map(signal -> ((GenerationEvent.TextDelta) ((GenerationSignal.Delta) signal).event()).text())
                            .reduce("", String::concat);
                    assertEquals(java.util.stream.IntStream.range(0, 400).mapToObj(i -> i + ",").reduce("", String::concat), actual);
                    assertEquals(1, subscriptions.get());
                }).verifyComplete();
    }

    @Test
    public void overflowingPendingQueueCancelsProviderAndFailsRatherThanDroppingOutput() {
        var cancelled = new AtomicInteger();
        var gate = Sinks.<Void>one();
        StepVerifier.create(GenerationOutputBatches.batch(Flux.range(0, GenerationOutputBatches.MAX_PENDING_SIGNALS * 2)
                                .map(i -> delta("字")).doFinally(type -> {
                                    if (type == reactor.core.publisher.SignalType.CANCEL) cancelled.incrementAndGet();
                                }))
                        .concatMap(batch -> gate.asMono().thenReturn(batch), 1))
                .then(() -> {
                    assertEquals(1, cancelled.get());
                    gate.tryEmitEmpty();
                })
                .thenConsumeWhile(ignored -> true)
                .expectErrorMatches(error -> error instanceof GenerationException
                        && error.getMessage().equals("OUTPUT_BUFFER_EXCEEDED"))
                .verify(Duration.ofSeconds(3));
        assertEquals(1, cancelled.get());
    }

    @Test
    public void cancellationStopsProviderRatherThanStartingAnotherSubscription() {
        var cancelled = new AtomicInteger();
        StepVerifier.create(GenerationOutputBatches.batch(Flux.concat(Flux.just(delta("首字")), Flux.<GenerationSignal>never())
                        .doFinally(type -> {
                            if (type == reactor.core.publisher.SignalType.CANCEL) cancelled.incrementAndGet();
                        })))
                .expectNextCount(1).thenCancel().verify(Duration.ofSeconds(2));
        assertEquals(1, cancelled.get());
    }
}
