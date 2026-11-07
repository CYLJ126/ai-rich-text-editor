package com.arte.ainew.config;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.control.*;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.application.execution.LocalDispatchNotifier;
import com.arte.ainew.application.execution.LocalExecutionEventNotifier;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.infrastructure.control.*;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.*;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.ainew.pojo.control.PublishedAiDefinitions;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/**
 * 显式开启的新 AI 受理装配；不扫描旧包，不执行 DDL／预算初始化，不启动 Worker 或模型客户端。
 * 持久化显式使用物理 DataSource Bean，避免旧 ThreadLocal 路由在调度线程失效。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({NewAiProperties.class, NewAiExecutionProperties.class})
public class NewAiAdmissionConfiguration {

    @Bean("newAiControlSourceSafety")
    org.springframework.beans.factory.SmartInitializingSingleton controlSourceSafety(ObjectProvider<AiControlConfigurationSource> sources) {
        return () -> {
            if (sources.stream().count() != 1) {
                throw new IllegalStateException("AI requires exactly one database control source");
            }
        };
    }

    /**
     * 应用层统一使用的 UTC 时钟，用于执行期限、上下文有效期及对象时间戳。
     * 可用同名 Bean 替换，便于测试；分布式租约仲裁仍使用存储层的数据库时钟。
     */
    @Bean("newAiClock")
    @ConditionalOnMissingBean(name = "newAiClock")
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * MyBatis／JDBC 阻塞事务的专用有界调度器，隔离响应式调用线程。
     * 线程及排队容量由 persistence 配置限制；应用关闭时释放调度器资源。
     */
    @Bean(name = "newAiPersistenceScheduler", destroyMethod = "dispose")
    Scheduler scheduler(NewAiProperties properties) {
        return Schedulers.newBoundedElastic(properties.persistence().threads(), properties.persistence().queuedTasks(), "arte-ainew-db");
    }

    /**
     * 执行记录、上下文快照及结果的内部 JSON 编解码器，采用版本信封和稳定类型别名白名单。
     * 与全局 HTTP ObjectMapper 隔离；可由自定义 ExecutionRecordCodec 替换。
     */
    @Bean("newAiRecordCodec")
    @ConditionalOnMissingBean(ExecutionRecordCodec.class)
    ExecutionRecordCodec codec() {
        return new JacksonExecutionRecordCodec();
    }

    /**
     * 单实例提交后唤醒／订阅通道，创建时不访问数据库或启动消费。
     */
    @Bean("newAiLocalExecutionEventNotifier")
    LocalExecutionEventNotifier notifier() {
        return new LocalExecutionEventNotifier();
    }

    /**
     * 派发提交提示通道，单独装配时不访问数据库、不启动 Worker。
     */
    @Bean("newAiLocalDispatchNotifier")
    LocalDispatchNotifier dispatchNotifier() {
        return new LocalDispatchNotifier();
    }

    /**
     * 执行权威存储，同时提供 ExecutionStore、ExecutionEventStore、ExecutionOutboxStore、
     * AdmissionCatalogStore 和 BudgetService，负责受理、执行状态、事件、Outbox 及账本事务。
     * 按配置名称取得物理数据源，使用独立 MyBatis 工厂和专用调度器，不继承旧 Mapper 插件。
     */
    @Bean("newAiExecutionPersistence")
    MybatisExecutionPersistence executionPersistence(NewAiProperties properties, ListableBeanFactory beans,
                                                     ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                     LocalExecutionEventNotifier notifier, LocalDispatchNotifier dispatchNotifier, NewAiExecutionProperties executionProperties) {
        return new MybatisExecutionPersistence(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler,
                notifier::wakePublisher, dispatchNotifier::committed, executionProperties.scheduling(), true);
    }

    /**
     * 不可变上下文及结果字节存储，提供 ContextSnapshotStore 和 ExecutionResultStore。
     * 使用与执行存储相同的物理数据源，但字节写入为独立事务；保存快照或结果不推进执行状态。
     */
    @Bean("newAiPayloadPersistence")
    MybatisPayloadPersistence payloadPersistence(NewAiProperties properties,
                                                 ListableBeanFactory beans,
                                                 ExecutionRecordCodec codec,
                                                 @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new MybatisPayloadPersistence(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler);
    }

    /**
     * 工具目录底层存储，复用物理数据源、内部 codec 和有界数据库线程；构造不访问数据库。
     * 不注册工具目录管理 API、不执行部署脚本、不把历史版本变为可执行能力。
     */
    @Bean("newAiToolCatalogStore")
    MybatisToolCatalogStore toolCatalogStore(NewAiProperties properties, ListableBeanFactory beans,
                                             ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                             @Qualifier("newAiClock") Clock clock) {
        return new MybatisToolCatalogStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, clock);
    }

    /**
     * 生命周期底层状态／命令审计存储；构造零 SQL，TOOL 关闭时也不主动访问新增表。
     */
    @Bean("newAiToolReleaseStore")
    MybatisToolReleaseStore toolReleaseStore(NewAiProperties properties, ListableBeanFactory beans,
                                             ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                             @Qualifier("newAiClock") Clock clock) {
        return new MybatisToolReleaseStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, clock);
    }

    /**
     * 完整本地目录刷新命令与审计存储；构造不读表、不加载来源。
     */
    @Bean("newAiToolDiscoveryManagementStore")
    MybatisToolDiscoveryManagementStore toolDiscoveryManagementStore(NewAiProperties properties, ListableBeanFactory beans,
                                                                     ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                                     @Qualifier("newAiClock") Clock clock, MybatisToolCatalogStore catalog) {
        return new MybatisToolDiscoveryManagementStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, clock, catalog);
    }

    /**
     * 私有绑定底层存储，复用当前服务器 limits；构造不读表、不自动创建绑定。
     */
    @Bean("newAiToolBindingStore")
    MybatisToolBindingStore toolBindingStore(NewAiProperties properties, ListableBeanFactory beans,
                                             ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                             @Qualifier("newAiClock") Clock clock, MybatisToolCatalogStore catalog, MybatisToolReleaseStore releases) {
        var limits = properties.limits();
        var policy = new ToolExecutionPolicy(limits.maximumTimeout(), 10, java.time.Duration.ZERO, limits.maxInputBytes(),
                limits.maxOutputBytes(), ToolExecutionPolicy.ApprovalMode.NONE);
        return new MybatisToolBindingStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, clock, policy, catalog, releases);
    }

    /**
     * 应用服务边界的授权检查器：重新解析当前授权，检查操作权限、主体一致性和剩余执行期限。
     * 同时约束固定配置中的绑定／预算使用资格，防止任务权限范围扩大。
     */
    @Bean("newAiAdmissionAuthorization")
    AdmissionAuthorization authorization(ExecutionAuthorizationResolver resolver, @Qualifier("newAiClock") Clock clock,
                                         AiControlConfigurationSource source, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                         ObjectProvider<ToolInvocationAccess> toolInvocations) {
        return new AdmissionAuthorization(resolver, clock, source, scheduler, toolInvocations.getIfAvailable());
    }

    /**
     * 从 Spring 已认证身份创建 ExecutionContext，生成执行／追踪 ID 并固定权限及总期限。
     * 支持 MVC、WebFlux 入口和可信执行记录的上下文恢复；不在异步线程读取旧 ThreadLocal 身份。
     */
    @Bean("newAiExecutionContextFactory")
    ExecutionContextFactory executionContextFactory(ExecutionAuthorizationResolver resolver, @Qualifier("newAiClock") Clock clock) {
        return new ExecutionContextFactory(resolver, clock);
    }

    /**
     * 按业务端口组合模型能力及绑定入口，不再把固定配置兼容门面注册为生产 Bean。
     */
    @Bean("newAiControlCatalog")
    ModelControlService controlCatalog(NewAiProperties properties, PublishedAiCatalog published,
                                       AiResourceEntitlementService entitlements, BudgetDefinitionProvider budgets, AiExecutionPolicyProvider policies,
                                       @Qualifier("newAiClock") Clock clock) {
        return new ModelControlService(published, entitlements, budgets, policies, ExecutionPolicies.from(properties.limits()), clock);
    }

    @Bean("newAiResourceEntitlementService")
    @ConditionalOnMissingBean(AiResourceEntitlementService.class)
    AiResourceEntitlementService modelEntitlements(AdmissionAuthorization authorization,
                                                   AiControlConfigurationSource source, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                   ObjectProvider<ModelAclAccess> modelAccess) {
        ModelAclAccess access = modelAccess.getIfAvailable();
        return new SourceAiResourceEntitlementService(source, scheduler, authorization, access);
    }

    /**
     * 连接准备与真正发送使用同一资格、授权和请求门控端口。
     */
    @Bean("newAiConnectionManager")
    ConnectionManager connectionManager(AiResourceEntitlementService entitlements, AdmissionAuthorization authorization,
                                        AiAdmissionAuthorizationGuard guard) {
        return new EntitledConnectionManager(entitlements, authorization, guard);
    }

    /**
     * 供管理入口显式调用的预算账户初始化服务，要求 ai:budget:admin 权限并检查账户归属。
     * 首次按服务器配置建账，重复调用保留已有 held／charged 和版本；注册 Bean 不触发初始化。
     */
    @Bean("newAiBudgetInitializer")
    BudgetAccountInitializer budgetInitializer(BudgetDefinitionProvider definitions, AdmissionAuthorization authorization, MybatisExecutionPersistence store) {
        return new BudgetAccountInitializer(definitions, authorization, store, store);
    }

    /**
     * 授权预算账户查询门面；注册 Bean 不读账本、不初始化账户。
     */
    @Bean("newAiBudgetAccountQueryService")
    BudgetAccountQueryService budgetQueryService(BudgetDefinitionProvider definitions, AdmissionAuthorization authorization, MybatisExecutionPersistence store) {
        return new BudgetAccountQueryService(definitions, authorization, store);
    }

    /**
     * 延迟读取实际网关的支持声明；注册时不创建模型客户端或访问任何数据源。
     */
    @Bean("newAiChatConfigurationQueryService")
    ChatConfigurationQueryService chatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                                                AiResourceEntitlementService entitlements, PublishedAiCatalog published,
                                                                BudgetDefinitionProvider definitions, ObjectProvider<ModelGateway> gateway, AiExecutionPolicyProvider policies) {
        return new ChatConfigurationQueryService(properties, authorization, entitlements, published, definitions, gateway::getIfAvailable, policies);
    }

    @Bean("newAiPublishedCatalog")
    @ConditionalOnMissingBean(PublishedAiCatalog.class)
    PublishedAiCatalog publishedCatalog(AdmissionAuthorization authorization,
                                        AiControlConfigurationSource source,
                                        @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        var adapter = controlReads(authorization, source, scheduler);
        return new PublishedAiCatalog() {
            public <T> reactor.core.publisher.Mono<T> inSnapshot(ExecutionContext context,
                                                                 java.util.function.Function<PublishedAiDefinitions, reactor.core.publisher.Mono<T>> work) {
                return adapter.inSnapshot(context, work);
            }

            public <T> T inCurrentTransaction(ExecutionContext context, java.util.function.Function<PublishedAiDefinitions, T> work) {
                return adapter.inCurrentTransaction(context, work);
            }

            public reactor.core.publisher.Mono<RateDefinition> historicalRate(com.arte.core.pojo.reference.DefinitionRef reference) {
                return adapter.historicalRate(reference);
            }
        };
    }

    @Bean("newAiBudgetDefinitionProvider")
    @ConditionalOnMissingBean(BudgetDefinitionProvider.class)
    BudgetDefinitionProvider budgetDefinitions(AdmissionAuthorization authorization,
                                               AiControlConfigurationSource source,
                                               @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        var adapter = controlReads(authorization, source, scheduler);
        return new BudgetDefinitionProvider() {
            public Mono<List<BudgetDefinition>> available(ExecutionContext context, Operation operation) {
                return adapter.available(context, operation);
            }

            public com.arte.ainew.pojo.budget.BudgetDefinition resolveInCurrentTransaction(String ref, ExecutionContext context, Operation operation) {
                return adapter.resolveInCurrentTransaction(ref, context, operation);
            }
        };
    }

    @Bean("newAiExecutionPolicyProvider")
    @ConditionalOnMissingBean(AiExecutionPolicyProvider.class)
    AiExecutionPolicyProvider executionPolicies(NewAiProperties properties, AiControlConfigurationSource source,
                                                @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new ConfigurationExecutionPolicyProvider(controlReader(source, scheduler), properties.limits());
    }

    @Bean("newAiAdmissionAuthorizationGuard")
    @ConditionalOnMissingBean(AiAdmissionAuthorizationGuard.class)
    AiAdmissionAuthorizationGuard admissionGuard(NewAiProperties properties, AdmissionAuthorization authorization,
                                                 AiResourceEntitlementService entitlements, BudgetDefinitionProvider budgets, AiExecutionPolicyProvider policies,
                                                 AiControlConfigurationSource source, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                 @Qualifier("newAiClock") Clock clock,
                                                 ObjectProvider<com.arte.ainew.spi.assistant.AssistantContextAccess> assistants) {
        return new ConfigurationAiAdmissionAuthorizationGuard(controlReader(source, scheduler), authorization,
                entitlements, budgets, policies, properties.limits(), clock).assistants(assistants.getIfAvailable());
    }

    private ControlConfigurationReader controlReader(AiControlConfigurationSource source, Scheduler scheduler) {
        return new ControlConfigurationReader(source, scheduler);
    }

    private ControlReadAdapter controlReads(AdmissionAuthorization authorization,
                                            AiControlConfigurationSource source, Scheduler scheduler) {
        return new SourceControlReadAdapter(source, scheduler, authorization);
    }

    /**
     * 会话应用服务，负责幂等创建，以及校验当前权限和归属后的会话／Turn 读取。
     * 使用新会话存储；助手适配启用后可选用固定 ChatProfile。
     */
    @Bean("newAiConversationService")
    ConversationService conversationService(MybatisExecutionPersistence store, AdmissionAuthorization authorization, @Qualifier("newAiClock") Clock clock,
                                            ObjectProvider<ConversationArchiveReader> archives,
                                            ObjectProvider<com.arte.ainew.spi.assistant.AssistantContextAccess> assistants) {
        return new DefaultConversationService(store, authorization, clock, archives.getIfAvailable(), assistants.getIfAvailable());
    }

    /**
     * 单条用户文本与最近十轮完整历史的上下文组装及快照读取服务，检查绑定、输入大小和上下文容量，标记 Token 估算。
     * assemble 只构造快照，由协调器受理时持久化；find 从字节存储读取并检查当前权限及归属。
     */
    @Bean("newAiContextService")
    TextContextService contextService(AdmissionAuthorization authorization, BindingManager bindings, MybatisPayloadPersistence store,
                                  NewAiProperties properties, @Qualifier("newAiClock") Clock clock,
                                  ConversationService conversations, MybatisExecutionPersistence executions,
                                      ObjectProvider<ToolChatRunStore> runs, AiExecutionPolicyProvider policies,
                                      ObjectProvider<com.arte.ainew.spi.assistant.AssistantContextAccess> assistants) {
        return new TextContextService(authorization, bindings, store, properties.limits(), clock,
                new ChatHistoryLoader(conversations, authorization, executions, store, store, runs.getIfAvailable()), policies)
                .assistants(assistants.getIfAvailable());
    }

    /**
     * 可靠受理协调器，复核授权、固定配置、上下文及预算账户，计算语义摘要并处理耐久幂等重放。
     * 保存快照后原子受理 Invocation、Turn、会话版本、事件及派发 Outbox，提交成功才返回回执。
     * 当前仅实现 submit，派发、核对和控制尚未启用；受理阶段不预留预算或调用模型。
     */
    @Bean("newAiInvocationCoordinator")
    @ConditionalOnProperty(prefix = "arte.ai-new-execution", name = "enabled", havingValue = "false", matchIfMissing = true)
    InvocationCoordinator invocationCoordinator(AdmissionAuthorization authorization, CapabilityCatalog capabilities, BindingManager bindings, MybatisExecutionPersistence executions,
                                                MybatisPayloadPersistence snapshots, NewAiProperties properties, @Qualifier("newAiClock") Clock clock,
                                                AiExecutionPolicyProvider policies, AiAdmissionAuthorizationGuard guard,
                                                PublishedAiCatalog published, BudgetDefinitionProvider definitions) {
        return new AdmissionInvocationCoordinator(authorization, capabilities, bindings, executions, snapshots, executions, properties, clock,
                policies, guard, published, definitions);
    }

    /**
     * 预算管理员的人工账单核对；授权、账本及审计回执使用现有持久化组件。
     */
    @Bean("newAiManualReconciliationService")
    com.arte.ainew.application.budget.ManualReconciliationService manualReconciliationService(
            AdmissionAuthorization authorization, BudgetAccountQueryService accounts, MybatisExecutionPersistence executions) {
        return new com.arte.ainew.application.budget.ManualReconciliationService(authorization, accounts, executions);
    }

    /**
     * 普通消息创建新 Turn，重新生成追加最新 Turn 的候选；均通过可靠受理事务创建独立 Invocation。
     */
    @Bean("newAiChatService")
    ChatService chatService(AdmissionAuthorization authorization, ConversationService conversations, BindingManager bindings,
                            ContextService contexts, InvocationCoordinator coordinator, @Qualifier("newAiClock") Clock clock, MybatisExecutionPersistence executions, NewAiProperties properties) {
        return new DefaultChatService(authorization, conversations, bindings, contexts, coordinator, clock, executions, executions, properties);
    }
}
