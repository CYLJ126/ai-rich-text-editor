package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 模块部署参数。模型、授权、连接、费率和预算定义只从数据库控制面读取。
 *
 * @param enabled AI 启用开关
 * @param dataSourceBean 物理数据源 Bean 名称
 * @param releaseRef 部署发布标识
 * @param persistence 数据库调度资源上限
 * @param limits 执行限制的部署上限
 */
@ConfigurationProperties(prefix = "arte.ai-new", ignoreUnknownFields = false)
public record NewAiProperties(boolean enabled, String dataSourceBean, String releaseRef,
                              Persistence persistence, Limits limits) {

    public NewAiProperties {
        dataSourceBean = dataSourceBean == null ? "appDataSource" : ContractChecks.id(dataSourceBean, "dataSourceBean");
        persistence = persistence == null ? new Persistence(4, 256) : persistence;
        limits = limits == null ? defaultLimits() : limits;
        if (enabled) ContractChecks.id(releaseRef, "releaseRef");
    }

    public static Limits defaultLimits() {
        return new Limits(65_536, 4096, 1_048_576, Duration.ofMinutes(2), Duration.ofMinutes(15));
    }

    /**
     * 配置 AI 数据库阻塞任务使用的有界调度器容量。
     *
     * @param threads     数据库调度器最多创建的线程数，默认 4
     * @param queuedTasks 数据库调度器每个底层线程允许排队的任务数上限，默认 256
     */
    public record Persistence(int threads, int queuedTasks) {
        public Persistence {
            ContractChecks.range(threads, "threads", 1, 64);
            ContractChecks.range(queuedTasks, "queuedTasks", 1, 10_000);
        }
    }

    /**
     * 配置 AI 执行的输入、输出、超时及输入快照有效期上限。
     *
     * @param maxInputBytes     最大输入字节数，默认 65,536
     * @param maxOutputTokens   单次模型调用允许的最大输出 Token 数，默认 4,096
     * @param maxOutputBytes    单次执行允许的最大输出字节数，默认 1,048,576
     * @param maximumTimeout    单次执行的最大超时时长，默认 2 分钟
     * @param snapshotRetention 输入快照有效时长，默认 15 分钟，不能短于 maximumTimeout；到期禁止新调用，不表示立即删除数据
     */
    public record Limits(int maxInputBytes, int maxOutputTokens, long maxOutputBytes,
                         Duration maximumTimeout, Duration snapshotRetention) {
        public Limits {
            ContractChecks.range(maxInputBytes, "maxInputBytes", 1, 1_000_000);
            ContractChecks.range(maxOutputTokens, "maxOutputTokens", 1, 1_000_000);
            ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32 * 1024 * 1024);
            ContractChecks.require(maximumTimeout != null && maximumTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                    && maximumTimeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid maximum timeout");
            ContractChecks.require(snapshotRetention != null && snapshotRetention.compareTo(maximumTimeout) >= 0
                    && snapshotRetention.compareTo(Duration.ofDays(30)) <= 0, "Invalid snapshot retention");
        }
    }

}
