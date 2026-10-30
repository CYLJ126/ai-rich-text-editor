package com.arte.ainew.admission;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiAdmissionConfiguration;
import com.arte.ainew.config.NewAiGenerationConfiguration;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 装配兼容验收：显式来源、启动零 IO、空文件种子、端口替换及真实受理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 16:50 ✾
 */
public class ControlPlaneAssemblyCompatibilityTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final NewAiProperties AUTHORITY = AdmissionFixture.properties();

    private static DataSource forbidden() {
        return new AbstractDataSource() {
            public Connection getConnection() {
                throw new AssertionError("Assembly must not open a database connection");
            }

            public Connection getConnection(String name, String password) {
                return getConnection();
            }
        };
    }

    private static final class Source implements AiControlConfigurationSource {
        final AtomicInteger reads = new AtomicInteger(), transactions = new AtomicInteger();

        public NewAiProperties configuration(ExecutionContext context) {
            reads.incrementAndGet();
            return AUTHORITY;
        }

        public NewAiProperties configurationInCurrentTransaction(ExecutionContext context) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            transactions.incrementAndGet();
            return AUTHORITY;
        }

        public NewAiProperties definitions() {
            throw new AssertionError("Assembly must not read the published database catalog");
        }

        public NewAiProperties.Grant grant(ExecutionContext context) {
            throw new AssertionError("Must use scoped definitions");
        }
    }

    private AnnotationConfigApplicationContext context(String mode, boolean fixedLists, DataSource dataSource) {
        var c = new AnnotationConfigApplicationContext();
        var values = new HashMap<String, Object>();
        if (fixedLists) ConfigurationAssemblyTest.flatten("arte.ai-new", AUTHORITY, values);
        values.put("arte.ai-new.enabled", "true");
        values.put("arte.ai-new.configuration-mode", mode);
        values.put("arte.ai-new.release-ref", AUTHORITY.releaseRef());
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control-assembly", values));
        c.registerBean("appDataSource", DataSource.class, () -> dataSource);
        c.register(NewAiAdmissionConfiguration.class);
        return c;
    }

    private void resolver(AnnotationConfigApplicationContext c) {
        c.registerBean(ExecutionAuthorizationResolver.class, () -> new FixedExecutionAuthorizationResolver(AUTHORITY));
    }

    @Test
    public void fixedModeRejectsAccidentallyInstalledDatabaseSource() {
        try (var c = context("FIXED", true, forbidden())) {
            var source = new Source();
            c.registerBean(AiControlConfigurationSource.class, () -> source);
            assertThrows(BeansException.class, c::refresh);
            assertEquals(0, source.reads.get());
        }
    }

    @Test
    public void databaseModeRejectsMissingAndAmbiguousSourcesEvenWithPrimary() {
        try (var c = context("DATABASE", false, forbidden())) {
            resolver(c);
            assertThrows(BeansException.class, c::refresh);
        }
        try (var c = context("DATABASE", false, forbidden())) {
            resolver(c);
            c.registerBean("sourceA", AiControlConfigurationSource.class, Source::new, bean -> bean.setPrimary(true));
            c.registerBean("sourceB", AiControlConfigurationSource.class, Source::new);
            assertThrows(BeansException.class, c::refresh);
        }
    }

    @Test
    public void databaseModeRequiresAnExplicitAuthorizationResolver() {
        try (var c = context("DATABASE", false, forbidden())) {
            c.registerBean(AiControlConfigurationSource.class, Source::new);
            assertThrows(BeansException.class, c::refresh);
        }
    }

    @Test
    public void databaseGenerationStartsWithoutFileDefinitionsOrAnySourceRead() {
        try (var c = context("DATABASE", false, forbidden())) {
            var source = new Source();
            resolver(c);
            c.registerBean(AiControlConfigurationSource.class, () -> source);
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("generation", Map.of("arte.ai-new-generation.enabled", "true")));
            c.register(NewAiGenerationConfiguration.class);
            c.refresh();
            assertTrue(c.getBeansOfType(FixedControlCatalog.class).isEmpty());
            assertTrue(c.getBean(NewAiProperties.class).bindings().isEmpty());
            assertNotNull(c.getBean(CapabilityCatalog.class));
            assertNotNull(c.getBean(BindingManager.class));
            assertNotNull(c.getBean(ModelGateway.class));
            assertTrue(c.getBean(DeepSeekGenerationProviderAdapter.class).supports(AUTHORITY.capabilities().getFirst()));
            assertEquals(0, source.reads.get());
            assertEquals(0, source.transactions.get());
        }
    }

    private JdbcDataSource database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
        return source;
    }

    @Test
    public void databaseAdmissionUsesSourceWithNoFixedGrantsModelsOrBudgets() {
        var database = database();
        var scheduler = Schedulers.newBoundedElastic(2, 32, "empty-file-compatibility");
        try (var c = context("DATABASE", false, database)) {
            var authority = new Source();
            resolver(c);
            c.registerBean(AiControlConfigurationSource.class, () -> authority);
            c.refresh();
            var f = new AdmissionFixture(database, scheduler);
            f.initializeBudget("alice");
            var context = f.context("alice", "empty-file");
            var conversations = c.getBean(com.arte.ainew.api.conversation.ConversationService.class);
            var conversation = conversations.create("source", null, List.of(), f.context("alice", "create")).block(WAIT);
            var receipt = c.getBean(ChatService.class).submit(f.chatRequest(conversation.conversationId(), 0, "hello", context), context).block(WAIT);
            assertNotNull(receipt);
            assertEquals(1, authority.transactions.get());
            assertEquals(1, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class).intValue());
        } finally {
            scheduler.dispose();
            new JdbcTemplate(database).execute("DROP ALL OBJECTS");
        }
    }

    @Test
    public void replacingBudgetPortControlsDiscoveryAndRealAdmissionWithoutLegacyFallback() {
        verifyVeto(true);
    }

    @Test
    public void replacingGuardControlsActualAdmissionWithoutCreatingRecords() {
        verifyVeto(false);
    }

    private void verifyVeto(boolean vetoBudget) {
        var database = database();
        var scheduler = Schedulers.newBoundedElastic(2, 32, "replaced-port-compatibility");
        var guardChecks = new AtomicInteger();
        try (var c = context("FIXED", true, database)) {
            c.registerBean(ModelGateway.class, () -> new ModelGateway() {
                public boolean supportsTextChat(com.arte.ainew.pojo.control.ResolvedBinding binding, com.arte.ainew.pojo.control.ConnectionDefinition connection) {
                    return true;
                }

                public Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                    throw new AssertionError("Must not send");
                }
            });
            if (vetoBudget)
                c.registerBean(BudgetDefinitionProvider.class, () -> (context, operation) -> Mono.just(List.of()));
            else c.registerBean(AiAdmissionAuthorizationGuard.class, () -> check -> {
                guardChecks.incrementAndGet();
                return Mono.error(new AccessDeniedException("Injected admission veto"));
            });
            c.refresh();
            assertTrue(c.getBeansOfType(FixedControlCatalog.class).isEmpty());
            var f = new AdmissionFixture(database, scheduler);
            f.initializeBudget("alice");
            var context = f.context("alice", "veto");
            if (vetoBudget)
                assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(context).block(WAIT).options().isEmpty());
            var conversation = c.getBean(com.arte.ainew.api.conversation.ConversationService.class).create("veto", null, List.of(), f.context("alice", "create")).block(WAIT);
            assertThrows(RuntimeException.class, () -> c.getBean(ChatService.class).submit(f.chatRequest(conversation.conversationId(), 0, "hello", context), context).block(WAIT));
            assertEquals(vetoBudget ? 0 : 1, guardChecks.get());
            var jdbc = new JdbcTemplate(database);
            for (var table : List.of("arte_ai_invocation", "arte_ai_turn", "arte_ai_outbox"))
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class).intValue());
        } finally {
            scheduler.dispose();
            new JdbcTemplate(database).execute("DROP ALL OBJECTS");
        }
    }

    @Test
    public void productionAssemblyAndHostAdaptersDoNotUseTheLegacyFacadeOrRawDefinitionLookup() throws Exception {
        for (var file : List.of("NewAiAdmissionConfiguration", "NewAiExecutionConfiguration", "NewAiGenerationConfiguration", "NewAiToolExecutionConfiguration", "NewAiToolChatExecutionConfiguration")) {
            var source = Files.readString(Path.of("src/main/java/com/arte/ainew/config/" + file + ".java"));
            assertFalse(file, source.contains("FixedControlCatalog"));
            assertFalse(file, source.contains("source.definitions()"));
        }
        var paid = Files.readString(Path.of("../arte-app/src/main/java/com/arte/app/platform/ai/PlatformPaidToolAccess.java"));
        assertFalse(paid.contains("NewAiProperties"));
        assertFalse(paid.contains("AiControlConfigurationSource"));
    }
}
