package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;

/**
 * 受信数据库控制面
 * <p>
 * 同步方法仅在启动或 AI 数据库调度线程调用；不从客户端接受授权配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface AiControlConfigurationSource {

    /**
     * 一致准备配置及其不可变发布版本；版本为空表示来源不支持轻量复核。
     */
    record PreparedConfiguration(NewAiProperties configuration, Long catalogRevision) {
        public PreparedConfiguration {
            java.util.Objects.requireNonNull(configuration);
            if (catalogRevision != null && catalogRevision < 1)
                throw new IllegalArgumentException("Invalid catalog revision");
        }
    }

    /**
     * 在本次调用中核验当前主体、范围和 context 的全部权限，返回包含该主体 Grant 的一致配置快照。
     * 调用方可在同一次提交／派发准备内复用快照；受理和实际发送边界重新读取，不能跨请求缓存授权。
     */
    NewAiProperties configuration(ExecutionContext context);

    /**
     * 为新的准备边界读取配置和版本凭证；默认来源不提供版本优化。
     *
     * @param context 可信执行上下文
     * @return 本次验证后的配置及可选发布版本
     */
    default PreparedConfiguration prepareConfiguration(ExecutionContext context) {
        return new PreparedConfiguration(configuration(context), null);
    }

    /**
     * 发送前重新检查当前资格。仅当前资格有效且配置版本一致时，才可复用配置内容。
     * 默认完整读取；实现不能只凭版本相同跳过身份、成员、策略和空间状态检查。
     *
     * @param context  需要重新验证的执行上下文
     * @param prepared 此次操作的可信准备结果
     * @return 可用于发送前校验的当前配置
     */
    default PreparedConfiguration revalidate(ExecutionContext context, PreparedConfiguration prepared) {
        return prepareConfiguration(context);
    }

    NewAiProperties definitions();

    NewAiProperties.Grant grant(ExecutionContext context);
}
