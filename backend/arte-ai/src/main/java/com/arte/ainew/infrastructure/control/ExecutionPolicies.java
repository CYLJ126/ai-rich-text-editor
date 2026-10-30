package com.arte.ainew.infrastructure.control;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiExecutionPolicy;

/**
 * 迁移期类型转换
 * <p>
 * 公共业务端口不暴露配置记录。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ExecutionPolicies {

    private ExecutionPolicies() {
    }

    public static AiExecutionPolicy from(NewAiProperties.Limits value) {
        return new AiExecutionPolicy(value.maxInputBytes(), value.maxOutputTokens(), value.maxOutputBytes(),
                value.maximumTimeout(), value.snapshotRetention());
    }

    public static NewAiProperties.Limits legacy(AiExecutionPolicy value) {
        return new NewAiProperties.Limits(value.maxInputBytes(), value.maxOutputTokens(), value.maxOutputBytes(),
                value.maximumTimeout(), value.snapshotRetention());
    }
}
