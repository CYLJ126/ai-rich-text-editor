package com.arte.app.platform.persistence;

import com.arte.core.spi.platform.PublicTransactionRunner;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.function.Supplier;

/**
 * Schedule the entire synchronous transaction, never an individual store call.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class PlatformPersistenceExecutor {
    private final Scheduler scheduler;
    private final PublicTransactionRunner transactions;

    public PlatformPersistenceExecutor(Scheduler scheduler, PublicTransactionRunner transactions) {
        this.scheduler = scheduler;
        this.transactions = transactions;
    }

    public <T> Mono<T> read(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(scheduler);
    }

    public <T> Mono<T> inTransaction(Supplier<T> work) {
        return read(() -> transactions.required(work));
    }
}
