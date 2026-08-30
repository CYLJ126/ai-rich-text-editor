package com.nip.ai.strategy.context;

import com.nip.ai.common.enums.ContextStrategyEnum;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * 上下文策略工厂
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:14 ✾
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextStrategyFactory {
    private final ApplicationContext applicationContext;
    private final Map<ContextStrategyEnum, ContextStrategy> strategyMap =
            new EnumMap<>(ContextStrategyEnum.class);

    @PostConstruct
    public void init() {
        strategyMap.put(ContextStrategyEnum.WINDOW,
                applicationContext.getBean("windowContextStrategy", ContextStrategy.class));
        strategyMap.put(ContextStrategyEnum.SUMMARY,
                applicationContext.getBean("summaryContextStrategy", ContextStrategy.class));
        strategyMap.put(ContextStrategyEnum.FULL,
                applicationContext.getBean("fullContextStrategy", ContextStrategy.class));
        log.info("[ContextStrategyFactory] 上下文策略加载完成，共 {} 种", strategyMap.size());
    }

    public ContextStrategy getStrategy(ContextStrategyEnum strategyEnum) {
        return strategyMap.getOrDefault(strategyEnum,
                strategyMap.get(ContextStrategyEnum.WINDOW));
    }
}
