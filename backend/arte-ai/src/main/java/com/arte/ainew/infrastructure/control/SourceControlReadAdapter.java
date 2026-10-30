package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 现有数据库控制面桥接适配器
 * <p>
 * 阻塞来源始终在专用数据库调度器读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public final class SourceControlReadAdapter extends ControlReadAdapter {

    private final AiControlConfigurationSource source;
    private final Scheduler scheduler;

    public SourceControlReadAdapter(AiControlConfigurationSource source, Scheduler scheduler, AdmissionAuthorization authorization) {
        super(new ControlConfigurationReader(source, scheduler), authorization);
        this.source = source;
        this.scheduler = scheduler;
    }

    @Override
    public Mono<RateDefinition> historicalRate(DefinitionRef reference) {
        return Mono.fromCallable(() -> {
            reference.requireType("rate");
            var value = source.historicalRate(reference);
            if (!reference.equals(value.definition()))
                throw new IllegalStateException("Historical rate reference mismatch");
            return rate(value);
        }).subscribeOn(scheduler);
    }
}
