package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

/**
 * 固定配置读取适配器；不初始化账户，不对外暴露配置结构。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public final class FixedControlReadAdapter extends ControlReadAdapter {

    private final NewAiProperties properties;

    public FixedControlReadAdapter(NewAiProperties properties, AdmissionAuthorization authorization) {
        super(new ControlConfigurationReader(properties), authorization);
        this.properties = properties;
    }

    @Override
    public Mono<RateDefinition> historicalRate(DefinitionRef reference) {
        return Mono.fromCallable(() -> {
            reference.requireType("rate");
            return properties.rates().stream().filter(r -> r.definition().equals(reference)).findFirst()
                    .map(ControlReadAdapter::rate).orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH));
        });
    }
}
