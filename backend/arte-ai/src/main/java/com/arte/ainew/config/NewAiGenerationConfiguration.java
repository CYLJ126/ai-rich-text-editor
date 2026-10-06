package com.arte.ainew.config;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.gateway.DefaultModelGateway;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.EnvironmentCredentialResolver;
import com.arte.ainew.infrastructure.http.GenerationJson;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekWire;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 第 4 步的显式装配，仅构造运行组件，不连接供应商、不执行派发或启动 Worker。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@AutoConfiguration(after = NewAiAdmissionConfiguration.class)
@ConditionalOnProperty(prefix = "arte.ai-new-generation", name = "enabled", havingValue = "true")
@ConditionalOnBean(AdmissionAuthorization.class)
@ConditionalOnMissingBean(ModelGateway.class)
@EnableConfigurationProperties(NewAiGenerationProperties.class)
public class NewAiGenerationConfiguration {

    /**
     * 默认凭据解析器，仅在未提供自定义 ConnectionCredentialResolver 时注册。
     * <p>
     * 装配时登记固定版本 SecretRef 与环境变量名称的映射；实际凭据值在发送时读取，不缓存到配置或连接句柄中。
     * 可通过自定义 Bean 接入 Vault／KMS 等凭据来源。
     *
     * @param properties 单次生成配置，包含 SecretRef 与环境变量名称的映射
     * @return 默认环境变量凭据解析器
     */
    @Bean("newAiConnectionCredentialResolver")
    @ConditionalOnMissingBean(ConnectionCredentialResolver.class)
    ConnectionCredentialResolver credentials(NewAiGenerationProperties properties) {
        return new EnvironmentCredentialResolver(properties);
    }

    /**
     * 受控 HTTP 连接运行时，负责连接借用、释放、失效及发送边界的授权和凭据解析。
     * <p>
     * 使用独立、有界的连接池、DNS 调度器和事件循环，并校验出口目标。
     * Spring 容器销毁时调用 close，释放该运行时拥有的资源；装配阶段不连接供应商。
     *
     * @param connections 当前连接定义及使用权限的解析入口
     * @param authorization 当前主体的准入授权校验器
     * @param credentials 发送时使用的凭据解析器
     * @param properties 出口白名单、连接池及传输上限配置
     * @param clock 新 AI 层时钟，用于运行期限校验
     * @return 由 Spring 管理生命周期的 HTTP 连接运行时
     */
    @Bean(name = "newAiHttpConnectionRuntime", destroyMethod = "close")
    HttpConnectionRuntime runtime(ConnectionManager connections, AdmissionAuthorization authorization,
                                  ConnectionCredentialResolver credentials, NewAiGenerationProperties properties,
                                  @Qualifier("newAiClock") Clock clock) {
        return new HttpConnectionRuntime(connections, authorization, credentials, properties, clock);
    }

    /**
     * DeepSeek 文本生成适配器，负责请求映射、响应校验及生成信号的有界聚合。
     * <p>
     * 从固定绑定中提取使用 deepseek 供应商及 chat-completions-sse/v1 协议的能力，去重后登记。
     * 缺少匹配绑定时装配失败；适配器使用独立、有界 JSON 编解码器，映射过程不执行网络调用。
     *
     * @param admission 已登记的固定能力、绑定和连接配置
     * @param generation 单次生成配置，包含响应帧解析上限
     * @return DeepSeek 请求及响应语义适配器
     */
    @Bean("newAiDeepSeekProviderAdapter")
    DeepSeekGenerationProviderAdapter provider(NewAiProperties admission, NewAiGenerationProperties generation) {
        var supported = admission.bindings().stream().filter(binding -> admission.connections().stream().anyMatch(connection ->
                        connection.definition().equals(binding.connection()) && connection.providerId().equals("deepseek")
                                && connection.protocol().equals(ChatCompletionsSseProtocolAdapter.DEFINITION)))
                .map(ResolvedBinding::capability).distinct().toList();
        ContractChecks.require(!supported.isEmpty(), "Generation requires a configured DeepSeek chat-completions-sse binding");
        return new DeepSeekGenerationProviderAdapter(supported, GenerationJson.mapper(generation.maxFrameBytes()));
    }

    /**
     * Chat Completions SSE 协议适配器，负责单次 HTTP POST、响应状态校验和 SSE 帧解码。
     * <p>
     * 使用连接运行时提供的 Lease 执行交换，保留 [DONE] 结束标记，并落实请求和响应帧字节上限。
     * DeepSeek 专有请求 DTO 保留在内部泛型边界，装配阶段不发送请求。
     *
     * @param properties 单次生成的请求及响应帧上限配置
     * @return DeepSeek 请求使用的 SSE 协议适配器
     */
    @Bean("newAiChatCompletionsSseProtocolAdapter")
    ChatCompletionsSseProtocolAdapter<DeepSeekWire.Request> protocol(NewAiGenerationProperties properties) {
        return new ChatCompletionsSseProtocolAdapter<>(GenerationJson.mapper(properties.maxFrameBytes()), properties);
    }

    /**
     * 单次文本模型网关，串联控制面校验、供应商映射、协议交换和连接资源管理。
     * <p>
     * 同一个固定目录同时提供 CapabilityCatalog 与 BindingManager；网关落实绝对期限和本地取消，
     * 每次订阅执行一次交互，不内部重试。Invocation 派发、预算及耐久终态提交由执行协调器负责。
     *
     * @param catalog 固定能力与绑定目录，负责当前授权下的解析及请求校验
     * @param connections 当前连接定义的解析入口
     * @param runtime 受控 HTTP 连接运行时
     * @param provider DeepSeek 请求及响应语义适配器
     * @param protocol Chat Completions SSE 协议适配器
     * @param clock 新 AI 层时钟，用于绝对期限控制
     * @return 内部单次模型交互网关
     */
    @Bean("newAiModelGateway")
    ModelGateway modelGateway(FixedControlCatalog catalog, ConnectionManager connections, HttpConnectionRuntime runtime,
                              DeepSeekGenerationProviderAdapter provider, ChatCompletionsSseProtocolAdapter<DeepSeekWire.Request> protocol,
                              @Qualifier("newAiClock") Clock clock) {
        return new DefaultModelGateway<>(catalog, catalog, connections, runtime, provider, protocol, clock);
    }
}
