package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.OutboxMessage.WorkClass;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 显式启用分类调度后才使用新索引／许可；默认关闭，部署增量并统一升级消费者后开启。
 * 不允许自动降级为无容量限制；生成的原模型并发／频率校验仍独立生效。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-execution.scheduling", ignoreUnknownFields = false)
public record NewAiSchedulingProperties(boolean enabled, Slots slots, int generationConcurrency, int shortConcurrency,
                                        int longConcurrency, int outcomeConcurrency, int workspaceConcurrency,
                                        int providerConcurrency,
                                        int maxQueued, int generationQueued, int shortQueued, int longQueued,
                                        int outcomeQueued,
                                        int workspaceQueued, int subjectQueued, Duration shortThreshold,
                                        Duration shortQueueTimeout,
                                        Duration longQueueTimeout, String toolManifest) {

    public NewAiSchedulingProperties {
        slots = slots == null ? new Slots(0, 0, 0, 0) : slots;
        generationConcurrency = positive(generationConcurrency, 16);
        shortConcurrency = positive(shortConcurrency, 16);
        longConcurrency = positive(longConcurrency, 8);
        outcomeConcurrency = positive(outcomeConcurrency, 4);
        workspaceConcurrency = positive(workspaceConcurrency, 2);
        providerConcurrency = positive(providerConcurrency, 8);
        maxQueued = positive(maxQueued, 1000);
        generationQueued = positive(generationQueued, 300);
        shortQueued = positive(shortQueued, 500);
        longQueued = positive(longQueued, 100);
        outcomeQueued = positive(outcomeQueued, 100);
        workspaceQueued = positive(workspaceQueued, 100);
        subjectQueued = positive(subjectQueued, 20);
        shortThreshold = duration(shortThreshold, Duration.ofSeconds(10));
        shortQueueTimeout = duration(shortQueueTimeout, Duration.ofSeconds(10));
        longQueueTimeout = duration(longQueueTimeout, Duration.ofSeconds(30));
        if (toolManifest != null && toolManifest.isBlank()) toolManifest = null;
        if (toolManifest != null) ContractChecks.digest(toolManifest, "toolManifest");
    }

    public static NewAiSchedulingProperties disabled() {
        return new NewAiSchedulingProperties(false, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null);
    }

    private static int positive(int value, int fallback) {
        int result = value == 0 ? fallback : value;
        ContractChecks.range(result, "scheduling limit", 1, 10000);
        return result;
    }

    private static Duration duration(Duration value, Duration fallback) {
        var result = value == null ? fallback : value;
        ContractChecks.require(result.compareTo(Duration.ofMillis(1)) >= 0 && result.compareTo(Duration.ofMinutes(5)) <= 0,
                "Invalid scheduling timeout");
        return result;
    }

    public int cluster(WorkClass type) {
        return switch (type) {
            case GENERATION -> generationConcurrency;
            case TOOL_SHORT -> shortConcurrency;
            case TOOL_LONG -> longConcurrency;
            case OUTCOME_CHECK -> outcomeConcurrency;
        };
    }

    public int queue(WorkClass type) {
        return switch (type) {
            case GENERATION -> generationQueued;
            case TOOL_SHORT -> shortQueued;
            case TOOL_LONG -> longQueued;
            case OUTCOME_CHECK -> outcomeQueued;
        };
    }

    /**
     * 各类独占槽位，不能借用核对保留槽；总量必须与原 concurrency 一致。
     */
    public record Slots(int generation, int toolShort, int toolLong, int outcomeCheck) {
        public Slots {
            generation = positive(generation, 2);
            toolShort = positive(toolShort, 2);
            toolLong = positive(toolLong, 1);
            outcomeCheck = positive(outcomeCheck, 1);
            ContractChecks.require(generation + toolShort + toolLong + outcomeCheck <= 32, "Too many local dispatch slots");
        }

        public int total() {
            return generation + toolShort + toolLong + outcomeCheck;
        }

        public int capacity(WorkClass type) {
            return switch (type) {
                case GENERATION -> generation;
                case TOOL_SHORT -> toolShort;
                case TOOL_LONG -> toolLong;
                case OUTCOME_CHECK -> outcomeCheck;
            };
        }
    }
}
