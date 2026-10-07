package com.arte.ainew.config;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.control.BudgetAccountInitializer;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.application.execution.LocalExecutionEventNotifier;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * 显式开启的新 AI 受理装配；不扫描旧包，不执行 DDL／预算初始化，不启动 Worker 或模型客户端。
 * 持久化显式使用物理 DataSource Bean，避免旧 ThreadLocal 路由在调度线程失效。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NewAiProperties.class)
public class NewAiAdmissionConfiguration {

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
     * 默认授权解析器：按已认证名称、租户和工作空间，从固定 grants 解析稳定主体及权限范围。
     * 已提供自定义 ExecutionAuthorizationResolver 时不注册此默认实现。
     */
    @Bean("newAiAuthorizationResolver")
    @ConditionalOnMissingBean(ExecutionAuthorizationResolver.class)
    ExecutionAuthorizationResolver resolver(NewAiProperties properties) {
        return new FixedExecutionAuthorizationResolver(properties);
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
     * 执行权威存储，同时提供 ExecutionStore、ExecutionEventStore、ExecutionOutboxStore、
     * AdmissionCatalogStore 和 BudgetService，负责受理、执行状态、事件、Outbox 及账本事务。
     * 按配置名称取得物理数据源，使用独立 MyBatis 工厂和专用调度器，不继承旧 Mapper 插件。
     */
    @Bean("newAiExecutionPersistence")
    MybatisExecutionPersistence executionPersistence(NewAiProperties properties, ListableBeanFactory beans,
                                                     ExecutionRecordCodec codec, @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, LocalExecutionEventNotifier notifier) {
        return new MybatisExecutionPersistence(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, notifier::wakePublisher);
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
     * 应用服务边界的授权检查器：重新解析当前授权，检查操作权限、主体一致性和剩余执行期限。
     * 同时约束固定配置中的绑定／预算使用资格，防止任务权限范围扩大。
     */
    @Bean("newAiAdmissionAuthorization")
    AdmissionAuthorization authorization(ExecutionAuthorizationResolver resolver, NewAiProperties properties, @Qualifier("newAiClock") Clock clock) {
        return new AdmissionAuthorization(resolver, properties, clock);
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
     * 固定版本控制面，同时提供 CapabilityCatalog 和 BindingManager，并解析连接及预算定义。
     * 装配时校验引用、Schema、费率／币种及归属；使用时检查当前授权与能力／连接启用状态。
     */
    @Bean("newAiControlCatalog")
    FixedControlCatalog controlCatalog(NewAiProperties properties, AdmissionAuthorization authorization, @Qualifier("newAiClock") Clock clock) {
        return new FixedControlCatalog(properties, authorization, clock);
    }

    /**
     * 连接定义查询门面，将 ConnectionManager 委托给固定控制面，返回当前主体可使用的固定版本连接。
     * 单独注册以避开控制面接口同签名 resolve 方法的返回类型冲突；不创建连接或发送网络请求。
     */
    @Bean("newAiConnectionManager")
    ConnectionManager connectionManager(FixedControlCatalog catalog) {
        return catalog::resolveConnection;
    }

    /**
     * 供管理入口显式调用的预算账户初始化服务，要求 ai:budget:admin 权限并检查账户归属。
     * 首次按服务器配置建账，重复调用保留已有 held／charged 和版本；注册 Bean 不触发初始化。
     */
    @Bean("newAiBudgetInitializer")
    BudgetAccountInitializer budgetInitializer(FixedControlCatalog catalog, AdmissionAuthorization authorization, MybatisExecutionPersistence store) {
        return new BudgetAccountInitializer(catalog, authorization, store, store);
    }

    /**
     * 授权预算账户查询门面；注册 Bean 不读账本、不初始化账户。
     */
    @Bean("newAiBudgetAccountQueryService")
    BudgetAccountQueryService budgetQueryService(FixedControlCatalog catalog, AdmissionAuthorization authorization, MybatisExecutionPersistence store) {
        return new BudgetAccountQueryService(catalog, authorization, store);
    }

    /**
     * 延迟读取实际网关的支持声明；注册时不创建模型客户端或访问任何数据源。
     */
    @Bean("newAiChatConfigurationQueryService")
    ChatConfigurationQueryService chatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                                                FixedControlCatalog catalog, ObjectProvider<ModelGateway> gateway) {
        return new ChatConfigurationQueryService(properties, authorization, catalog, gateway::getIfAvailable);
    }

    /**
     * 会话应用服务，负责幂等创建，以及校验当前权限和归属后的会话／Turn 读取。
     * 使用新会话存储；当前不支持历史选择、ChatProfile 或资源配置。
     */
    @Bean("newAiConversationService")
    ConversationService conversationService(MybatisExecutionPersistence store, AdmissionAuthorization authorization, @Qualifier("newAiClock") Clock clock) {
        return new DefaultConversationService(store, authorization, clock);
    }

    /**
     * 单条用户文本与最近十轮完整历史的上下文组装及快照读取服务，检查绑定、输入大小和上下文容量，标记 Token 估算。
     * assemble 只构造快照，由协调器受理时持久化；find 从字节存储读取并检查当前权限及归属。
     */
    @Bean("newAiContextService")
    ContextService contextService(AdmissionAuthorization authorization, FixedControlCatalog catalog, MybatisPayloadPersistence store,
                                  NewAiProperties properties, @Qualifier("newAiClock") Clock clock,
                                  ConversationService conversations, MybatisExecutionPersistence executions) {
        return new TextContextService(authorization, catalog, store, properties, clock,
                new ChatHistoryLoader(conversations, authorization, executions, store, store));
    }

    /**
     * 可靠受理协调器，复核授权、固定配置、上下文及预算账户，计算语义摘要并处理耐久幂等重放。
     * 保存快照后原子受理 Invocation、Turn、会话版本、事件及派发 Outbox，提交成功才返回回执。
     * 当前仅实现 submit，派发、核对和控制尚未启用；受理阶段不预留预算或调用模型。
     */
    @Bean("newAiInvocationCoordinator")
    @ConditionalOnProperty(prefix = "arte.ai-new-execution", name = "enabled", havingValue = "false", matchIfMissing = true)
    InvocationCoordinator invocationCoordinator(AdmissionAuthorization authorization, FixedControlCatalog catalog, MybatisExecutionPersistence executions,
                                                MybatisPayloadPersistence snapshots, NewAiProperties properties, @Qualifier("newAiClock") Clock clock) {
        return new AdmissionInvocationCoordinator(authorization, catalog, catalog, executions, snapshots, executions, properties, clock);
    }

    /**
     * 聊天应用入口，串联会话校验、绑定解析、上下文组装及新 Turn 构造，再交给协调器可靠受理。
     * 当前支持单条用户文本提交，返回 AcceptedExecution；重新生成尚未支持。
     */
    @Bean("newAiChatService")
    ChatService chatService(AdmissionAuthorization authorization, ConversationService conversations, FixedControlCatalog bindings,
                            ContextService contexts, InvocationCoordinator coordinator, @Qualifier("newAiClock") Clock clock) {
        return new DefaultChatService(authorization, conversations, bindings, contexts, coordinator, clock);
    }
}
