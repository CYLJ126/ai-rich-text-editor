package com.arte.app.ainew;

import com.alibaba.druid.pool.DruidDataSource;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.control.BudgetAccountInitializer;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiExecutionConfiguration;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式手动联调：读取 profile/app.properties 的物理数据源及凭据环境变量，写入实际数据库。
 * AI 配置使用 Maven 过滤后的运行资源；修改 profile 中的 AI 参数后须重新构建资源。
 * IT 后缀不会被默认 Surefire 单元测试扫描；需指定类或方法运行。
 * 不加载 arte-app 的 H2 测试配置，不执行 DDL，不启动自动 Worker 或旧 AI 组件。
 * 测试身份仅用于此受信本地入口，生产入口必须使用实际认证身份。
 */
public class NewAiManualIT {

    private static final String BUDGET = "example-budget";
    private static final Duration DB_WAIT = Duration.ofSeconds(45);

    @Configuration(proxyBeanMethods = false)
    public static class PhysicalDataSourceConfiguration {
        @Bean(name = "appDataSource", destroyMethod = "close")
        @ConfigurationProperties("spring.datasource.druid.app")
        DruidDataSource appDataSource() {
            // 精简测试容器不加载 Boot 数据源自动配置，直接使用连接池并绑定物理数据源参数。
            return new DruidDataSource();
        }
    }

    private AnnotationConfigApplicationContext openContext() throws Exception {
        var context = new AnnotationConfigApplicationContext();
        try {
            // Maven 默认工作目录为 backend/arte-app；IDE 也应设置成这个目录。
            loadConfiguration(context, new FileSystemResource("../profile/app.properties"));
            context.register(PhysicalDataSourceConfiguration.class, NewAiAdmissionConfiguration.class,
                    NewAiGenerationConfiguration.class, NewAiExecutionConfiguration.class);
            context.refresh();
            return context;
        } catch (Exception | Error error) {
            context.close();
            throw error;
        }
    }

    /**
     * 本地 profile 是构建参数，不能用其不完整的 AI 列表覆盖生成后的运行配置。
     */
    static void loadConfiguration(AnnotationConfigApplicationContext context, Resource localProfile) throws IOException {
        var loader = new PropertiesPropertySourceLoader();
        loader.load("runtime-ai", new ClassPathResource("ainew/config/application.properties"))
                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
        var dataSource = new LinkedHashMap<String, Object>();
        for (var source : loader.load("local-profile", localProfile)) {
            for (var name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                if (name.startsWith("spring.datasource.druid.app.")) {
                    dataSource.put(name, source.getProperty(name));
                }
            }
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-data-source", dataSource));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-only",
                Map.of("arte.ai-new-execution.worker-enabled", "false")));
    }

    private ExecutionContext executionContext(AnnotationConfigApplicationContext context, Set<String> scopes, String key) {
        var properties = context.getBean(NewAiProperties.class);
        var name = System.getProperty("arte.ai-new.manual.subject", "zhangsc");
        var budget = properties.budgets().stream().filter(value -> value.budgetRef().equals(BUDGET)).findFirst().orElseThrow();
        // 只有测试在本地构造已认证身份，仍由固定 grants 验证授权。
        var authentication = UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of());
        return Objects.requireNonNull(context.getBean(ExecutionContextFactory.class).create(authentication,
                        new ExecutionContextRequest(budget.owner().tenantId(), budget.owner().workspaceId(), scopes,
                                properties.limits().maximumTimeout(), null, BUDGET, properties.releaseRef(), key))
                .block(Duration.ofSeconds(10)));
    }

    /**
     * 按服务端固定配置初始化 {@value #BUDGET} 预算账户，要求当前测试身份具有 ai:budget:admin 权限。
     * <p>
     * 此方法只访问一张业务表：{@code arte_ai_account}（预算账户表）。具体操作如下：
     * <ol>
     *     <li>先按预算引用哈希查询账户快照，并校验租户、工作空间和主体归属。</li>
     *     <li>账户不存在时插入一条记录：{@code id_key} 保存预算引用哈希，{@code owner_key} 保存归属哈希；
     *         {@code snapshot} 保存预算引用、归属、配置限额及币种、固定费率引用，
     *         初始占用金额 {@code held=0}、已计费金额 {@code charged=0}、账本版本 {@code version=0}。
     *         插入后重新查询并返回实际持久化账户；并发插入冲突时也重新查询，不覆盖已有记录。</li>
     *     <li>账户已存在时只读取并核对配置，不执行 UPDATE，不清零 held、charged 或版本，
     *         也不按新配置调整限额；归属、限额或固定费率不匹配时直接报错。</li>
     * </ol>
     * 权限、预算定义及费率来自配置，不读写数据库中的用户、菜单或权限表。
     * 此方法不访问 {@code arte_ai_reservation}（预算预留表）或 {@code arte_ai_settlement}（预算结算事实表），
     * 不创建会话、轮次、调用、Attempt、事件、Outbox 或结果记录，也不调用模型。
     * block 会订阅初始化操作并等待完成，最后仅打印账户当前版本、held 和 charged。
     *
     * @param context 已加载物理数据源及新 AI 固定配置的测试容器
     */
    private void initializeBudget(AnnotationConfigApplicationContext context) {
        var admin = executionContext(context, Set.of("ai:budget:admin"), "budget-initialize");
        var account = context.getBean(BudgetAccountInitializer.class).initialize(BUDGET, admin).block(DB_WAIT);
        assertNotNull(account);
        System.out.println("Budget ready: " + account.budgetRef() + ", version=" + account.version()
                + ", held=" + account.held() + ", charged=" + account.charged());
    }

    /**
     * 仅准备预算；重复运行不清空 held／charged，不调用模型。
     */
    @Test
    public void initializeExampleBudget() throws Exception {
        try (var context = openContext()) {
            initializeBudget(context);
        }
    }

    /**
     * 消费数据库中一批待派发消息；没有消息返回 0，处理数量不等于成功生成数量。
     */
    @Test
    public void dispatchOneBatch() throws Exception {
        try (var context = openContext()) {
            var timeout = context.getBean(NewAiProperties.class).limits().maximumTimeout().plusMinutes(1);
            var processed = context.getBean(InvocationDispatchWorker.class).pollOnce().block(timeout);
            assertNotNull(processed);
            System.out.println("Dispatch messages processed: " + processed);
        }
    }

    /**
     * 初始化预算、提交新的会话／文本、手动派发、查询真实模型结果；每次运行创建新调用。
     * <p>
     * 提交落库 → 手动消费派发 → 等待批次结束 → 查询状态 → 读取持久化结果 → 打印文本
     */
    @Test
    public void submitTextAndDispatch() throws Exception {
        try (var context = openContext()) {
            // BudgetAccountInitializer：初始化预算
            initializeBudget(context);
            var properties = context.getBean(NewAiProperties.class);
            // ConversationService：获取聊天服务类
            var conversation = context.getBean(ConversationService.class).create("新 AI 手动测试", null, List.of(),
                    executionContext(context, Set.of("ai:conversation"), "conversation-" + UUID.randomUUID())).block(DB_WAIT);
            assertNotNull(conversation);
            // ExecutionContext：准备执行上下文
            var execution = executionContext(context, Set.of("ai:invoke", "ai:conversation", "ai:read"),
                    "submit-" + UUID.randomUUID());
            var binding = properties.bindings().stream()
                    .filter(value -> value.definition().id().equals("default-text")).findFirst().orElseThrow();
            int outputTokens = Math.min(128, properties.limits().maxOutputTokens());
            long inputTokens = Math.min(1024, binding.contextWindowTokens() - outputTokens);
            // ChatMessage：用户消息
            var user = new ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER,
                    List.of(new ChatMessage.Text("请只回复：新 AI 链路测试成功。")), List.of(), null);
            // ContextRequest：生成上下文请求
            var selection = new ContextRequest(List.of(user), null, List.of(), List.of(), null,
                    new ContextBudget(binding.contextWindowTokens(), inputTokens, outputTokens, 0));
            // EntryRequests：生成聊天请求
            var request = new EntryRequests.Chat(conversation.conversationId(), conversation.version(), null, null,
                    selection, binding.capability().definition(), binding.definition(),
                    new GenerationOptions(outputTokens, null, null, List.of()),
                    new ExecutionOptions(execution.deadline(), 1, properties.limits().maxOutputBytes(), 0, 0,
                            properties.limits().maximumTimeout()));
            // ChatService：提交聊天请求，调用 InvocationCoordinator.submit 受理并落库
            var accepted = context.getBean(ChatService.class).submit(request, execution).block(DB_WAIT);
            assertNotNull(accepted);
            System.out.println("Accepted execution: " + accepted.executionId());
            // 一批可能先领取其他执行；有界地继续手动领取，直到本次调用结束。
            long stopAt = System.nanoTime() + properties.limits().maximumTimeout().plusMinutes(1).toNanos();
            Invocation status;
            do {
                var remaining = Duration.ofNanos(Math.max(1, stopAt - System.nanoTime()));
                // InvocationDispatchWorker：手动派发，ExecutionOutboxStore.claim() 领取任务，InvocationCoordinator.dispatch() 执行任务
                // block() 触发订阅，并阻塞当前 JUnit 测试线程，直到本批处理完成或等待超时
                context.getBean(InvocationDispatchWorker.class).pollOnce().block(remaining);
                var read = executionContext(context, Set.of("ai:read"), "read-" + UUID.randomUUID());
                // ExecutionControl：从数据库查询执行状态，ExecutionControl.status() 验证读取权限，再按所属主体和调用 ID 查询 Invocation
                status = Objects.requireNonNull(context.getBean(ExecutionControl.class)
                        .status(accepted.executionId(), read).block(DB_WAIT));
                if (status.state().terminal()) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(100); // 仅测试线程等待，不阻塞响应式事件线程。
            } while (System.nanoTime() < stopAt);
            assertEquals(Invocation.State.SUCCEEDED, status.state(), () -> "Execution " + accepted.executionId()
                    + " did not succeed; inspect its persisted status/error");
            var read = executionContext(context, Set.of("ai:read"), "result-" + UUID.randomUUID());
            // 从数据库读取已提交的结果，ExecutionEventService.result() 先查 Invocation，取得其中的权威结果引用，再读取对应结果记录
            var result = context.getBean(ExecutionEventService.class).result(accepted.executionId(), read).block(DB_WAIT);
            var generation = assertInstanceOf(InvocationResult.Generation.class, result);
            assertTrue(generation.value().complete());
            // 处理模型输出
            generation.value().outputs().stream().flatMap(message -> message.content().stream())
                    .filter(ChatMessage.Text.class::isInstance).map(ChatMessage.Text.class::cast)
                    .forEach(text -> System.out.println("Model reply: " + text.text()));
        }
    }
}
