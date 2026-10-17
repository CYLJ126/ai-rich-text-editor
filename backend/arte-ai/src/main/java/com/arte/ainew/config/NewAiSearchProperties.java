package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 独立搜索开关；默认不探活、不建索引、不启动扫描。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-search", ignoreUnknownFields = false)
public record NewAiSearchProperties(boolean enabled, boolean queryEnabled, boolean scanEnabled,
                                    boolean backfillEnabled, boolean reconcileEnabled,
                                    Index index, Session session, Limits limits, Sync sync,
                                    boolean initializeOnStartup, Diagnostics diagnostics) {

    @ConstructorBinding
    public NewAiSearchProperties {
        diagnostics = diagnostics == null ? new Diagnostics(null, null) : diagnostics;
        sync = sync == null ? new Sync(null, null, null, null, null, null, null, null, null) : sync;
        ContractChecks.require(!(initializeOnStartup || scanEnabled || backfillEnabled || reconcileEnabled) || sync.generation() != null, "Search initialization/background work requires a fixed physical generation");
        index = index == null ? new Index(null, null, null, null, null, null, null, null, null, null) : index;
        ContractChecks.require(sync.generation() == null || sync.generation().startsWith(index.generationPrefix() + "-")
                && !sync.generation().equals(index.readAlias()) && !sync.generation().equals(index.writeAlias()), "Physical generation must match configured prefix");
        session = session == null ? new Session(null, null, null, null, null) : session;
        limits = limits == null ? new Limits(null, null, null, null, null, null, null) : limits;
        ContractChecks.require(enabled || !(initializeOnStartup || queryEnabled || scanEnabled || backfillEnabled || reconcileEnabled),
                "Search master switch must be enabled before sub-switches");
    }

    public NewAiSearchProperties(boolean enabled, boolean queryEnabled, boolean scanEnabled, boolean backfillEnabled,
                                 boolean reconcileEnabled, Index index, Session session, Limits limits, Sync sync,
                                 boolean initializeOnStartup) {
        this(enabled, queryEnabled, scanEnabled, backfillEnabled, reconcileEnabled, index, session, limits, sync, initializeOnStartup, null);
    }

    /**
     * 仅控制搜索后台工厂；成功 SQL 默认静默，失败和慢 SQL 始终保留。
     */
    public record Diagnostics(Boolean sqlEnabled, Duration slowSqlThreshold) {
        public Diagnostics {
            sqlEnabled = Boolean.TRUE.equals(sqlEnabled);
            slowSqlThreshold = duration(slowSqlThreshold, Duration.ofMillis(500), Duration.ofMillis(1), Duration.ofMinutes(1));
        }
    }

    public NewAiSearchProperties(boolean enabled, boolean queryEnabled, boolean scanEnabled, boolean backfillEnabled,
                                 boolean reconcileEnabled, Index index, Session session, Limits limits, Sync sync) {
        this(enabled, queryEnabled, scanEnabled, backfillEnabled, reconcileEnabled, index, session, limits, sync, false);
    }

    // 保持第一阶段构造契约；新扫描参数默认关闭。
    public NewAiSearchProperties(boolean enabled, boolean queryEnabled, boolean scanEnabled, boolean backfillEnabled,
                                 boolean reconcileEnabled, Index index, Session session, Limits limits) {
        this(enabled, queryEnabled, scanEnabled, backfillEnabled, reconcileEnabled, index, session, limits, null);
    }

    public record Sync(String generation, Duration tick, Integer batchSize, Integer workPerTick, Duration lease,
                       Duration reconcileInterval, Duration retryInitial, Duration retryMax, Integer errorThreshold,
                       Duration tickBudget, Duration idleMax) {
        @ConstructorBinding
        public Sync {
            generation = generation == null || generation.isBlank() ? null : generation;
            ContractChecks.require(generation == null || generation.matches("[a-z][a-z0-9_-]{0,127}"), "Invalid physical generation");
            tickBudget = duration(tickBudget, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(30));
            tick = duration(tick, Duration.ofMillis(250), Duration.ofMillis(100), Duration.ofMinutes(1));
            idleMax = duration(idleMax, tick.compareTo(Duration.ofSeconds(5)) > 0 ? tick : Duration.ofSeconds(5), tick, Duration.ofMinutes(1));
            batchSize = batchSize == null ? 20 : batchSize;
            workPerTick = workPerTick == null ? 10 : workPerTick;
            errorThreshold = errorThreshold == null ? 10 : errorThreshold;
            ContractChecks.range(batchSize, "batchSize", 1, 100);
            ContractChecks.range(workPerTick, "workPerTick", 1, 100);
            ContractChecks.range(errorThreshold, "errorThreshold", 1, 1000);
            lease = duration(lease, Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofMinutes(10));
            reconcileInterval = duration(reconcileInterval, Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofDays(7));
            retryInitial = duration(retryInitial, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMinutes(5));
            retryMax = duration(retryMax, Duration.ofMinutes(5), retryInitial, Duration.ofHours(1));
        }

        public Sync(String generation, Duration tick, Integer batchSize, Integer workPerTick, Duration lease,
                    Duration reconcileInterval, Duration retryInitial, Duration retryMax, Integer errorThreshold, Duration tickBudget) {
            this(generation, tick, batchSize, workPerTick, lease, reconcileInterval, retryInitial, retryMax, errorThreshold, tickBudget, null);
        }

        public Sync(String generation, Duration tick, Integer batchSize, Integer workPerTick, Duration lease,
                    Duration reconcileInterval, Duration retryInitial, Duration retryMax, Integer errorThreshold) {
            this(generation, tick, batchSize, workPerTick, lease, reconcileInterval, retryInitial, retryMax, errorThreshold, null);
        }
    }

    public static NewAiSearchProperties defaults() {
        return new NewAiSearchProperties(false, false, false, false, false, null, null, null);
    }

    public record Index(String readAlias, String writeAlias, String generationPrefix,
                        String indexTokenizer, String queryTokenizer, String rulesVersion,
                        String expectedServerVersion, String expectedIkVersion, Integer shards, Integer replicas) {
        public Index {
            readAlias = name(readAlias, "conversation-search-read");
            writeAlias = name(writeAlias, "conversation-search-write");
            generationPrefix = name(generationPrefix, "conversation-search-v2");
            ContractChecks.require(!readAlias.equals(writeAlias), "Read and write aliases must differ");
            indexTokenizer = tokenizer(indexTokenizer);
            queryTokenizer = tokenizer(queryTokenizer);
            rulesVersion = rulesVersion == null ? "v2" : ContractChecks.id(rulesVersion, "rulesVersion");
            expectedServerVersion = version(expectedServerVersion);
            expectedIkVersion = version(expectedIkVersion);
            ContractChecks.require(expectedServerVersion.equals(expectedIkVersion), "ES and IK versions must match");
            shards = shards == null ? 1 : shards;
            replicas = replicas == null ? 1 : replicas;
            ContractChecks.range(shards, "shards", 1, 32);
            ContractChecks.range(replicas, "replicas", 0, 5);
        }

        private static String tokenizer(String v) {
            v = v == null ? "ik_smart" : v;
            ContractChecks.require(v.equals("ik_smart") || v.equals("ik_max_word"), "IK tokenizer required");
            return v;
        }

        private static String name(String v, String fallback) {
            v = v == null ? fallback : v;
            ContractChecks.require(v.matches("[a-z][a-z0-9_-]{0,100}"), "Invalid search index name");
            return v;
        }

        private static String version(String v) {
            v = v == null ? "9.4.2" : v;
            ContractChecks.require(v.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "Exact dependency version required");
            return v;
        }
    }

    public record Session(String namespace, Duration idleTimeout, Duration maxLifetime,
                          Integer maxPerSubject, Duration redisTimeout) {
        public Session {
            namespace = namespace == null ? "arte:ai-new:conversation-search:v1" : namespace;
            ContractChecks.require(namespace.matches("[a-zA-Z0-9:_-]{1,128}"), "Invalid Redis namespace");
            idleTimeout = duration(idleTimeout, Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofHours(1));
            maxLifetime = duration(maxLifetime, Duration.ofMinutes(30), idleTimeout, Duration.ofHours(24));
            maxPerSubject = maxPerSubject == null ? 3 : maxPerSubject;
            ContractChecks.range(maxPerSubject, "maxPerSubject", 1, 20);
            redisTimeout = duration(redisTimeout, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(30));
        }
    }

    public record Limits(Integer maxPageSize, Integer candidateBudget, Integer maxEsRequests,
                         Duration requestTimeout, Integer previewMaxChars, Integer ioThreads, Integer queuedTasks) {
        public Limits {
            maxPageSize = maxPageSize == null ? 20 : maxPageSize;
            candidateBudget = candidateBudget == null ? 100 : candidateBudget;
            maxEsRequests = maxEsRequests == null ? 5 : maxEsRequests;
            previewMaxChars = previewMaxChars == null ? 800 : previewMaxChars;
            ioThreads = ioThreads == null ? 2 : ioThreads;
            queuedTasks = queuedTasks == null ? 1000 : queuedTasks;
            ContractChecks.range(maxPageSize, "maxPageSize", 1, 20);
            ContractChecks.range(candidateBudget, "candidateBudget", maxPageSize, 1000);
            ContractChecks.range(maxEsRequests, "maxEsRequests", 1, 20);
            ContractChecks.range(previewMaxChars, "previewMaxChars", 1, 800);
            ContractChecks.range(ioThreads, "ioThreads", 1, 16);
            ContractChecks.range(queuedTasks, "queuedTasks", 1, 10000);
            requestTimeout = duration(requestTimeout, Duration.ofSeconds(3), Duration.ofMillis(100), Duration.ofSeconds(30));
        }
    }

    private static Duration duration(Duration value, Duration fallback, Duration min, Duration max) {
        value = value == null ? fallback : value;
        ContractChecks.require(value.compareTo(min) >= 0 && value.compareTo(max) <= 0, "Invalid search duration");
        return value;
    }
}
