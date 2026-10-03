package com.arte.app.ainew;

import com.arte.ai.api.control.BindingManager;
import com.arte.ai.api.control.CapabilityCatalog;
import com.arte.ai.api.control.ConnectionManager;
import com.arte.ai.api.execution.BudgetService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.ai.model.capability.CapabilityKind;
import com.arte.ai.model.capability.SideEffectKind;
import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.definition.DefinitionStatus;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.app.security.bridge.ExistingEgressPolicy;
import com.arte.app.security.bridge.JdbcSecurityRepository;
import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.model.security.SecretRef;
import com.arte.base.spi.observability.AuditSink;
import com.arte.base.spi.observability.Telemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 显式开启独立最小模型链，需要身份和执行支撑 Bean；密钥可来自编译配置或环境变量，不自动授予预算／许可。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "arte.ai-new.model.enabled", havingValue = "true")
public class NewModelConfiguration {
    @Bean
    public ConfiguredModelDefinitions newModelDefinitions(
            @Value("${arte.ai-new.model.tenant-id}") String tenant, @Value("${arte.ai-new.model.workspace-id}") String workspace,
            @Value("${arte.ai-new.model.version}") String version, @Value("${arte.ai-new.model.endpoint}") URI endpoint,
            @Value("${arte.ai-new.model.secret-env}") String secretEnv) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !secretEnv.matches("[A-Z][A-Z0-9_]{1,127}"))
            throw new IllegalArgumentException("invalid model endpoint or credential reference");
        var capability = new CapabilityDefinition(new CapabilityDescriptor(new DefinitionRef("ai-capability", "default-model", version), CapabilityKind.MODEL, null, null, Set.of("text", "non-streaming", "streaming"), SideEffectKind.EXTERNAL_EFFECT), DefinitionStatus.PUBLISHED);
        var connection = new ConnectionDefinition(new DefinitionRef("ai-connection", "default-model", version), "compatible-chat", "chat-completions", endpoint, new SecretRef(secretEnv, null), DefinitionStatus.PUBLISHED);
        return new ConfiguredModelDefinitions(capability, connection, new DefinitionRef("ai-binding", "default-model", version), tenant, workspace);
    }

    @Bean
    public JdbcModelExecutionStore newModelStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcModelExecutionStore(jdbc, manager, Clock.systemUTC());
    }

    @Bean
    public BudgetQuote newModelBudgetQuote(@Value("${arte.ai-new.model.maximum-call-cost}") BigDecimal maximum, @Value("${arte.ai-new.model.currency}") String currency) {
        return new BudgetQuote(maximum, currency);
    }

    @Bean
    public BudgetService newModelBudgets(BudgetQuote quote, JdbcModelExecutionStore store) {
        return new BudgetService(quote, store);
    }

    @Bean
    public PinnedHttpConnectionRuntime newModelRuntime(@Value("${arte.ai-new.model.endpoint}") URI endpoint,
                                                       @Value("${arte.ai-new.model.secret-env}") String env,
                                                       @Value("${arte.ai-new.model.api-key:}") String apiKey) {
        return new PinnedHttpConnectionRuntime(Set.of(endpoint.getHost()), modelCredentials(env, apiKey, System::getenv), 1048576);
    }

    static Function<SecretRef, char[]> modelCredentials(String env, String apiKey, Function<String, String> environment) {
        return ref -> {
            if (!env.equals(ref.secretId())) throw new IllegalArgumentException("unknown credential reference");
            String secret = apiKey.isBlank() ? environment.apply(env) : apiKey;
            return secret == null ? null : secret.toCharArray();
        };
    }

    @Bean
    public CompatibleChatProviderAdapter newModelProvider(PinnedHttpConnectionRuntime runtime, BudgetQuote quote,
                                                          @Value("${arte.ai-new.model.model-name}") String model, @Value("${arte.ai-new.model.input-token-price}") BigDecimal input,
                                                          @Value("${arte.ai-new.model.output-token-price}") BigDecimal output,
                                                          @Value("${arte.ai-new.model.max-input-bytes:16384}") int bytes, @Value("${arte.ai-new.model.max-output-tokens:2048}") int tokens,
                                                          @Value("${arte.ai-new.model.reasoning-effort:}") String reasoningEffort) {
        return new CompatibleChatProviderAdapter(runtime, model, input, output, quote, bytes, tokens, reasoningEffort);
    }

    @Bean
    public ExistingModelAccessPolicy newModelAccess(JdbcSecurityRepository repository, @Value("${arte.ai-new.model.application-id:ai-new-model}") String application) {
        return new ExistingModelAccessPolicy(repository, Clock.systemUTC(), application);
    }

    @Bean
    public InvocationCoordinator newModelCoordinator(ConfiguredModelDefinitions definitions, CompatibleChatProviderAdapter provider, ExistingModelAccessPolicy access,
                                                     ExistingEgressPolicy egress, LocalAdmissionController admission, BoundedTaskExecutor tasks, JdbcModelExecutionStore store, BudgetService budgets, AuditSink audit, ObjectProvider<Telemetry> telemetry, JdbcModelWorkQueue queue) {
        return new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(definitions), new ConnectionManager(definitions), new BindingManager(definitions)),
                new DefaultModelGateway(List.of(provider)), access, egress, admission, tasks, store, store, budgets, audit, Clock.systemUTC(), telemetry.getIfAvailable(Telemetry::disabled), queue);
    }

    @Bean
    public JdbcModelWorkQueue newModelWorkQueue(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcModelExecutionStore store,
                                                @Value("${arte.execution.support.tenant-id}") String tenant,
                                                @Value("${arte.execution.support.threads:4}") int threads,
                                                @Value("${arte.execution.support.queue-capacity:32}") int queued,
                                                @Value("${arte.execution.support.starts-per-minute:60}") int starts,
                                                @Value("${arte.execution.support.lease-duration:PT30S}") String lease,
                                                @Value("${arte.execution.support.poll-interval:PT0.5S}") String poll) {
        if (Duration.parse(lease).compareTo(Duration.parse(poll).multipliedBy(3)) < 0)
            throw new IllegalArgumentException("worker lease must exceed three poll intervals");
        return new JdbcModelWorkQueue(jdbc, manager, store, tenant, threads, queued, starts, Duration.parse(lease));
    }

    @Bean
    public DurableModelWorker newDurableModelWorker(JdbcModelWorkQueue queue, InvocationCoordinator coordinator, BoundedTaskExecutor tasks,
                                                    LocalAdmissionController admission, ObjectProvider<Telemetry> telemetry,
                                                    @Value("${arte.execution.support.threads:4}") int threads,
                                                    @Value("${arte.execution.support.poll-interval:PT0.5S}") String poll,
                                                    @Value("${arte.execution.support.shutdown-grace:PT10S}") String grace) {
        return new DurableModelWorker(queue, coordinator, tasks, admission, telemetry.getIfAvailable(Telemetry::disabled), threads, Duration.parse(poll), Duration.parse(grace));
    }

    @Bean
    public NewModelCallService newModelCalls(InvocationCoordinator coordinator, ExecutionContextFactory contexts, EgressConsentService consents, ConfiguredModelDefinitions definitions,
                                             @Value("${arte.ai-new.model.application-id:ai-new-model}") String application) {
        return new NewModelCallService(coordinator, contexts, consents, definitions, application);
    }
}
