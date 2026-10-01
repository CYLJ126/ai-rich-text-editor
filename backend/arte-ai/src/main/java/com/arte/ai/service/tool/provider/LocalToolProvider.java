package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.annotation.ToolRelease;
import com.arte.ai.common.annotation.ToolRisk;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolRiskProfile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 本地 Java 工具提供者
 * <p>
 * 搜索本地定义的实现了 {@link Tool} 及 {@link ToolCallback} 及 {@link org.springframework.ai.tool.annotation.Tool} 的工具方法，
 * 转为工具提供者 {@link LocalToolProvider}。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Service
public class LocalToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "local-java";
    public static final String SPRING_AI_NAMESPACE = "local";
    public static final String DEFAULT_VERSION = "1.0.0";

    private final ObjectProvider<Tool<?, ?>> domainTools;
    private final ObjectProvider<ToolCallback> springAiCallbacks;
    private final SpringAiToolObjectLocator toolObjectLocator;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final String springAiNamespace;
    private final String springAiVersion;
    private volatile Map<ToolReference, Tool<?, ?>> tools = Map.of();

    public LocalToolProvider(ObjectProvider<Tool<?, ?>> domainTools,
                             ObjectProvider<ToolCallback> springAiCallbacks,
                             SpringAiToolObjectLocator toolObjectLocator,
                             ObjectMapper objectMapper,
                             @Qualifier("toolCallbackExecutor") Executor executor,
                             @Value("${arte.ai.tool.local.namespace:" + SPRING_AI_NAMESPACE + "}")
                             String springAiNamespace,
                             @Value("${arte.ai.tool.local.version:" + DEFAULT_VERSION + "}")
                             String springAiVersion) {
        this.domainTools = domainTools;
        this.springAiCallbacks = springAiCallbacks;
        this.toolObjectLocator = toolObjectLocator;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.springAiNamespace = springAiNamespace;
        this.springAiVersion = springAiVersion;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "Local Java Tools";
    }

    @Override
    public ToolProviderTypeEnum getProviderType() {
        return ToolProviderTypeEnum.LOCAL;
    }

    @Override
    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .sorted(Comparator.comparing(definition -> definition.reference().toString()))
                .toList();
    }

    @Override
    public Optional<Tool<?, ?>> resolve(ToolReference reference) {
        return Optional.ofNullable(tools.get(reference));
    }

    @Override
    public CompletionStage<Void> refresh() {
        Map<ToolReference, Tool<?, ?>> refreshed = new LinkedHashMap<>();
        domainTools.orderedStream().forEach(tool -> putUnique(refreshed, tool));
        springAiCallbacks.orderedStream()
                .map(this::adapt)
                .forEach(tool -> putUnique(refreshed, tool));
        for (Object toolObject : toolObjectLocator.findToolObjects()) {
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(toolObject).build().getToolCallbacks();
            Map<String, ToolRiskProfile> riskProfiles = methodRiskProfiles(toolObject);
            Map<String, ToolRelease> releases = methodReleases(toolObject);
            for (ToolCallback callback : callbacks) {
                ToolRiskProfile risk = riskProfiles.getOrDefault(callback.getToolDefinition().name(),
                        SpringAiToolAdapter.localRiskProfile());
                putUnique(refreshed, adapt(callback, risk, releases.get(callback.getToolDefinition().name())));
            }
        }
        tools = Map.copyOf(refreshed);
        return CompletableFuture.completedFuture(null);
    }

    private SpringAiToolAdapter adapt(ToolCallback callback) {
        return adapt(callback, SpringAiToolAdapter.localRiskProfile());
    }

    private SpringAiToolAdapter adapt(ToolCallback callback, ToolRiskProfile risk) {
        return adapt(callback, risk, null);
    }

    private SpringAiToolAdapter adapt(ToolCallback callback, ToolRiskProfile risk, ToolRelease release) {
        ToolExecutionPolicy defaults = SpringAiToolAdapter.defaultExecutionPolicy();
        // 定义校验要求破坏性或严重风险工具的默认策略也声明人工审批。
        ToolExecutionPolicy policy = new ToolExecutionPolicy(defaults.executionMode(), defaults.timeout(),
                defaults.maxRetries(), defaults.retryBackoff(), defaults.maxOutputTokens(),
                defaults.requiresApproval() || risk.destructive() || risk.level() == ToolRiskLevelEnum.CRITICAL,
                defaults.allowsResultCache());
        return new SpringAiToolAdapter(
                springAiNamespace,
                release == null ? springAiVersion : release.version(),
                callback,
                risk,
                policy,
                objectMapper,
                executor,
                release == null ? Set.of() : Set.copyOf(Arrays.asList(release.compatibleWith())));
    }

    private Map<String, ToolRelease> methodReleases(Object toolObject) {
        Map<String, ToolRelease> releases = new HashMap<>();
        Class<?> toolClass = ClassUtils.getUserClass(AopUtils.getTargetClass(toolObject));
        for (var method : ReflectionUtils.getDeclaredMethods(toolClass)) {
            if (!ReflectionUtils.USER_DECLARED_METHODS.matches(method)
                    || !AnnotatedElementUtils.hasAnnotation(method, org.springframework.ai.tool.annotation.Tool.class))
                continue;
            ToolRelease release = AnnotatedElementUtils.findMergedAnnotation(method, ToolRelease.class);
            if (release != null) {
                if (release.version().isBlank())
                    throw new IllegalArgumentException("ToolRelease.version must not be blank");
                releases.put(ToolUtils.getToolName(method), release);
            }
        }
        return releases;
    }

    private Map<String, ToolRiskProfile> methodRiskProfiles(Object toolObject) {
        Map<String, ToolRiskProfile> profiles = new HashMap<>();
        Class<?> toolClass = ClassUtils.getUserClass(AopUtils.getTargetClass(toolObject));
        for (var method : ReflectionUtils.getDeclaredMethods(toolClass)) {
            if (!ReflectionUtils.USER_DECLARED_METHODS.matches(method)
                    || !AnnotatedElementUtils.hasAnnotation(method, org.springframework.ai.tool.annotation.Tool.class)) {
                continue;
            }
            ToolRisk annotation = AnnotatedElementUtils.findMergedAnnotation(method, ToolRisk.class);
            if (annotation != null) {
                profiles.put(ToolUtils.getToolName(method), new ToolRiskProfile(annotation.level(),
                        annotation.readOnly(), annotation.destructive(), annotation.reversible(),
                        annotation.idempotent(), annotation.openWorld(),
                        Set.copyOf(Arrays.asList(annotation.requiredScopes())),
                        Set.copyOf(Arrays.asList(annotation.allowedNetworkTargets()))));
            }
        }
        return profiles;
    }

    private void putUnique(Map<ToolReference, Tool<?, ?>> target, Tool<?, ?> tool) {
        ToolReference reference = tool.getDefinition().reference();
        if (target.putIfAbsent(reference, tool) != null) {
            throw new IllegalStateException("duplicate local tool: " + reference);
        }
    }
}
