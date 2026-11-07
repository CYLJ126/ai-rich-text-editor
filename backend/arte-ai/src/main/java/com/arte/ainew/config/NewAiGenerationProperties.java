package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 模型生成网关的部署开关、环境凭据映射及 SSE 排空期限
 * <p>
 * HTTP 资源参数由 {@link NewAiHttpProperties} 独立管理。
 *
 * @param enabled 是否装配模型生成网关，默认关闭
 * @param secrets 固定版本凭据引用与环境变量名称的映射；实际凭据在发送时读取
 * @param completedStreamDrainTimeout SSE 结束标记后的响应尾部排空期限，默认 100 毫秒
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-generation", ignoreUnknownFields = false)
public record NewAiGenerationProperties(boolean enabled, List<SecretEnvironment> secrets,
                                        Duration completedStreamDrainTimeout) {

    public NewAiGenerationProperties {
        secrets = secrets == null ? List.of() : ContractChecks.list(secrets, "secrets", 0, 256);
        ContractChecks.unique(secrets.stream().map(SecretEnvironment::reference).toList(), "secret references");
        completedStreamDrainTimeout = ChatCompletionsSseOptions.defaults(completedStreamDrainTimeout).completedStreamDrainTimeout();
    }

    /**
     * 固定协议大小上限不开放为部署属性，独立提供给 SSE 协议适配器。
     */
    public ChatCompletionsSseOptions protocolOptions() {
        return ChatCompletionsSseOptions.defaults(completedStreamDrainTimeout);
    }

    /**
     * 受信凭据引用到进程环境变量名称的映射
     * <p>
     * 不保存环境变量值。
     *
     * @param reference 精确版本的凭据引用，类型必须为 secret
     * @param environmentVariable 实际发送时读取的环境变量名称
     */
    public record SecretEnvironment(DefinitionRef reference, String environmentVariable) {

        public SecretEnvironment {
            Objects.requireNonNull(reference).requireType("secret");
            ContractChecks.require(environmentVariable != null && environmentVariable.matches("[A-Z_][A-Z0-9_]{0,127}"),
                    "Invalid credential environment variable name");
        }
    }
}
