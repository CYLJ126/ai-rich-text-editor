package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.control.BudgetAccountInitializer;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * 受信固定配置与真实存储装配
 * <p>
 * 这些配置用于测试，连接地址是占位地址；测试不访问用户当前数据库，不调用真实模型供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
final class AdmissionFixture {
    /**
     * 文本生成能力的固定版本引用：capability / text / v1
     */
    static final DefinitionRef CAP = new DefinitionRef("capability", "text", "v1");
    /**
     * 能力与连接、模型、上下文容量及费率之间的绑定引用：binding / text / v1
     */
    static final DefinitionRef BINDING = new DefinitionRef("binding", "text", "v1");
    /**
     * 测试连接定义的引用：connection / text / v1；具体配置包含协议、地址、凭据引用和超时
     */
    static final DefinitionRef CONNECTION = new DefinitionRef("connection", "text", "v1");
    /**
     * 测试费率版本引用：rate / text / v1；实际费率在 properties() 中声明
     */
    static final DefinitionRef RATE = new DefinitionRef("rate", "text", "v1");
    /**
     * 两分钟的测试执行期限，也用于连接读取超时及请求的相对期限
     */
    static final Duration TIMEOUT = Duration.ofMinutes(2);
    /**
     * 普通调用上下文的权限集合：ai:invoke、ai:conversation、ai:read
     * 不包含预算管理权限。initializeBudget() 会单独创建只请求 ai:budget:admin 的上下文，以验证普通调用与预算管理的权限边界。
     */
    static final Set<String> REGULAR = Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ);

    final NewAiProperties properties;
    final ExecutionRecordCodec codec;
    final ExecutionContextFactory factory;
    final MybatisExecutionPersistence executions;
    final MybatisPayloadPersistence payloads;
    final FixedControlCatalog catalog;
    final BudgetAccountInitializer budgets;
    final TextContextService contexts;
    final DefaultConversationService conversations;
    final AdmissionInvocationCoordinator coordinator;
    final DefaultChatService chat;

    static ExecutionOwner owner(String subject) {
        return new ExecutionOwner("tenant", "workspace", subject);
    }

    static Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CNY"));
    }

    static NewAiProperties properties() {
        var capabilities = new CapabilityDescriptor(CAP, CapabilityDescriptor.Kind.GENERATION, FixedControlCatalog.INPUT_SCHEMA,
                FixedControlCatalog.OUTPUT_SCHEMA, Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING),
                CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
        var grantScopes = new HashSet<>(REGULAR);
        grantScopes.add(AdmissionAuthorization.BUDGET_ADMIN);
        var grants = List.of(new NewAiProperties.Grant("alice", "alice-id", ExecutionPrincipal.Kind.USER, "tenant", "workspace",
                        "alice-grant-v1", true, grantScopes, Set.of("text"), Set.of("alice-budget")),
                new NewAiProperties.Grant("bob", "bob-id", ExecutionPrincipal.Kind.USER, "tenant", "workspace",
                        "bob-grant-v1", true, grantScopes, Set.of("text"), Set.of("bob-budget")));
        var connection = new ConnectionDefinition(CONNECTION, "test-provider", new DefinitionRef("protocol", "http", "v1"),
                URI.create("https://example.invalid/v1"), new DefinitionRef("secret", "test-credential", "v1"),
                ConnectionDefinition.State.ENABLED, Duration.ofSeconds(5), TIMEOUT, 4096);
        return new NewAiProperties(true, "appDataSource", "release-v1", new NewAiProperties.Persistence(4, 256),
                new NewAiProperties.Limits(2048, 256, 4096, TIMEOUT, Duration.ofMinutes(15)), grants, List.of(capabilities),
                List.of(new ResolvedBinding(BINDING, capabilities, CONNECTION, "test-model", 1024L, RATE)), List.of(connection),
                List.of(new NewAiProperties.Rate(RATE, money("1"), money("2"))),
                List.of(new NewAiProperties.Budget("alice-budget", owner("alice-id"), money("100"), RATE),
                        new NewAiProperties.Budget("bob-budget", owner("bob-id"), money("100"), RATE)));
    }

    AdmissionFixture(DataSource dataSource, Scheduler scheduler) {
        this(dataSource, scheduler, new JacksonExecutionRecordCodec(), properties());
    }

    AdmissionFixture(DataSource dataSource, Scheduler scheduler, ExecutionRecordCodec codec, NewAiProperties properties) {
        this(dataSource, scheduler, codec, properties, () -> {
        });
    }

    AdmissionFixture(DataSource dataSource, Scheduler scheduler, ExecutionRecordCodec codec, NewAiProperties properties, Runnable wakeup) {
        this.properties = properties;
        this.codec = codec;
        var clock = Clock.systemUTC();
        var resolver = new FixedExecutionAuthorizationResolver(properties);
        var authorization = new AdmissionAuthorization(resolver, properties, clock);
        factory = new ExecutionContextFactory(resolver, clock);
        executions = new MybatisExecutionPersistence(dataSource, codec, scheduler, wakeup);
        payloads = new MybatisPayloadPersistence(dataSource, codec, scheduler);
        catalog = new FixedControlCatalog(properties, authorization, clock);
        budgets = new BudgetAccountInitializer(catalog, authorization, executions, executions);
        conversations = new DefaultConversationService(executions, authorization, clock);
        contexts = new TextContextService(authorization, catalog, payloads, properties, clock,
                new ChatHistoryLoader(conversations, authorization, executions, payloads, payloads));
        coordinator = new AdmissionInvocationCoordinator(authorization, catalog, catalog, executions, payloads, executions, properties, clock);
        chat = new DefaultChatService(authorization, conversations, catalog, contexts, coordinator, clock, executions, executions, properties);
    }

    ExecutionContext context(String name, String key) {
        return context(name, key, REGULAR);
    }

    ExecutionContext context(String name, String key, Set<String> scopes) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of());
        return factory.create(authentication, new ExecutionContextRequest("tenant", "workspace", scopes, TIMEOUT, null,
                name + "-budget", "release-v1", key)).block(Duration.ofSeconds(5));
    }

    void initializeBudget(String name) {
        budgets.initialize(name + "-budget", context(name, "budget-initialize", Set.of(AdmissionAuthorization.BUDGET_ADMIN))).block();
    }

    EntryRequests.Chat chatRequest(String conversationId, long expectedVersion, String text, ExecutionContext context) {
        var user = new ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER, List.of(new ChatMessage.Text(text)), List.of(), null);
        var selection = new ContextRequest(List.of(user), null, List.of(), List.of(), null, new ContextBudget(1024, 768, 256, 0));
        return new EntryRequests.Chat(conversationId, expectedVersion, null, null, selection, CAP, BINDING,
                new GenerationOptions(128, null, null, List.of()), new ExecutionOptions(context.deadline(), 1, 4096, 0, 0, TIMEOUT));
    }
}
