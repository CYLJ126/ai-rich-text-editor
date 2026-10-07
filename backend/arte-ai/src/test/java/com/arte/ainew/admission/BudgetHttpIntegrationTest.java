package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiBudgetController;
import com.arte.ainew.web.request.BudgetRequests;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * MVC＋隔离 H2＋真实授权及账本，准备预算事实后仅通过 HTTP 读取，不调用真实模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public class BudgetHttpIntegrationTest {
    private static final String PATH = "/ai-new/budget/getBudget";
    private static final Map<String, String> SCOPE = Map.of("tenantId", "tenant", "workspaceId", "workspace");
    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture fixture;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @Before
    public void setup() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        scheduler = Schedulers.newBoundedElastic(4, 128, "budget-http-db");
        fixture = new AdmissionFixture(dataSource, scheduler);
        fixture.initializeBudget("alice");
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        configureMvc(fixture.properties);
        login("alice");
    }

    private void configureMvc(NewAiProperties properties) {
        var clock = Clock.systemUTC();
        var resolver = new FixedExecutionAuthorizationResolver(properties);
        var authorization = new AdmissionAuthorization(resolver, properties, clock);
        var service = new BudgetAccountQueryService(new FixedControlCatalog(properties, authorization, clock), authorization, fixture.executions);
        mvc = standaloneSetup(new NewAiBudgetController(service,
                new NewAiHttpContext(new ExecutionContextFactory(resolver, clock), properties), properties))
                .setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).setAsyncRequestTimeout(10000).build();
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        validator.close();
        jdbc.execute("DROP ALL OBJECTS");
        scheduler.dispose();
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
    }

    private Map<String, Object> query(String ref) {
        var request = new LinkedHashMap<String, Object>();
        request.put("scope", SCOPE);
        request.put("budgetRef", ref);
        return request;
    }

    private JsonNode send(Map<String, ?> request, int status) throws Exception {
        return finish(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "en")
                .content(json.writeValueAsString(request))).andReturn(), status);
    }

    private JsonNode finish(MvcResult result, int status) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private BudgetCommands.Account account() {
        return fixture.executions.account(AdmissionFixture.owner("alice-id"), "alice-budget").block();
    }

    private BudgetReservation reserve(String amount) {
        var context = fixture.context("alice", "submit-" + UUID.randomUUID());
        var conversation = fixture.conversations.create("budget test", null, List.of(),
                fixture.context("alice", "create-" + UUID.randomUUID())).block();
        var accepted = fixture.chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "hello", context), context).block();
        var owner = AdmissionFixture.owner("alice-id");
        var invocation = fixture.executions.find(owner, accepted.executionId()).block();
        var attempt = fixture.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                new ExecutionCommands.Version(owner, accepted.executionId(), invocation.version()), "attempt-" + UUID.randomUUID(),
                "budget-test-worker", Duration.ofMinutes(1))).block().value();
        invocation = fixture.executions.find(owner, accepted.executionId()).block();
        var reserved = fixture.executions.reserve(new BudgetCommands.Reserve(ExecutionCommands.Guard.from(owner, invocation, attempt),
                "reservation-" + UUID.randomUUID(), AdmissionFixture.money(amount), AdmissionFixture.RATE, Duration.ofDays(1))).block();
        assertTrue(reserved.successful());
        return reserved.value();
    }

    private void settle(BudgetReservation reservation, BudgetSettlement.State state, String charge) {
        var settlement = new BudgetSettlement("settlement-" + UUID.randomUUID(), reservation.reservationId(), state,
                state == BudgetSettlement.State.SETTLED ? new Usage(Usage.Basis.PROVIDER_REPORTED, 3L, 2L, 5L) : Usage.unknown(),
                charge == null ? null : AdmissionFixture.money(charge), Instant.now());
        var evidence = switch (state) {
            case SETTLED -> BudgetCommands.Evidence.PROVIDER_BILL;
            case RELEASED -> BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED;
            case PENDING_RECONCILIATION -> BudgetCommands.Evidence.UNKNOWN_COST;
        };
        assertTrue(fixture.executions.settle(new BudgetCommands.Settle(AdmissionFixture.owner("alice-id"), reservation.version(),
                settlement, evidence, state == BudgetSettlement.State.PENDING_RECONCILIATION ? null : "test-evidence")).block().successful());
    }

    @Test
    public void initialAccountResponseIsPreciseAndRepeatedReadsDoNotWriteOrExposeOwner() throws Exception {
        var before = account();
        var response = send(query("alice-budget"), 200);
        assertEquals(ResultCodeEnum.SUCCESS.getDesc(Locale.ENGLISH), response.path("desc").asString());
        assertTrue(response.path("success").asBoolean());
        var data = response.path("data");
        assertEquals("alice-budget", data.path("budgetRef").asString());
        assertEquals("CNY", data.path("currency").asString());
        assertEquals("100", data.path("limit").asString());
        assertEquals("0", data.path("held").asString());
        assertEquals("0", data.path("charged").asString());
        assertEquals("100", data.path("available").asString());
        assertEquals(0, data.path("version").asLong());
        assertEquals("rate", data.path("rateVersion").path("type").asString());
        assertFalse(data.has("owner"));
        assertFalse(data.has("authorization"));
        assertEquals(response, send(query("alice-budget"), 200));
        assertEquals(before, account());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Long.class).longValue());
    }

    @Test
    public void reservationAndSettlementAreReadFromOneAccountSnapshotWithoutRounding() throws Exception {
        var reservation = reserve("0.123456789012345678");
        var reserved = send(query("alice-budget"), 200).path("data");
        assertTrue(reserved.path("held").isString());
        assertEquals("0.123456789012345678", reserved.path("held").asString());
        assertEquals("99.876543210987654322", reserved.path("available").asString());
        assertEquals(1, reserved.path("version").asLong());
        settle(reservation, BudgetSettlement.State.SETTLED, "0.000000000000000001");
        var settled = send(query("alice-budget"), 200).path("data");
        assertEquals("0", settled.path("held").asString());
        assertEquals("0.000000000000000001", settled.path("charged").asString());
        assertEquals("99.999999999999999999", settled.path("available").asString());
        assertEquals(2, settled.path("version").asLong());
    }

    @Test
    public void pendingReconciliationKeepsHeldAndDoesNotInventCharges() throws Exception {
        var reservation = reserve("5");
        settle(reservation, BudgetSettlement.State.PENDING_RECONCILIATION, null);
        var before = account();
        var data = send(query("alice-budget"), 200).path("data");
        assertEquals("5", data.path("held").asString());
        assertEquals("0", data.path("charged").asString());
        assertEquals("95", data.path("available").asString());
        assertEquals(before, account());
        assertEquals(BudgetReservation.State.PENDING_RECONCILIATION,
                fixture.executions.reservation(AdmissionFixture.owner("alice-id"), reservation.reservationId()).block().state());
    }

    @Test
    public void provenReleaseRestoresAvailableBalance() throws Exception {
        var reservation = reserve("10");
        settle(reservation, BudgetSettlement.State.RELEASED, "0");
        var data = send(query("alice-budget"), 200).path("data");
        assertEquals("0", data.path("held").asString());
        assertEquals("0", data.path("charged").asString());
        assertEquals("100", data.path("available").asString());
    }

    @Test
    public void actualChargesBeyondLimitKeepNegativeAvailableBalance() throws Exception {
        var reservation = reserve("10");
        settle(reservation, BudgetSettlement.State.SETTLED, "113.25");
        var data = send(query("alice-budget"), 200).path("data");
        assertEquals("100", data.path("limit").asString());
        assertEquals("113.25", data.path("charged").asString());
        assertEquals("-13.25", data.path("available").asString());
    }

    @Test
    public void authorizedButUninitializedBudgetIsNotCreatedByReading() throws Exception {
        login("bob");
        var response = send(query("bob-budget"), 400);
        assertEquals(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED.getCode(), response.path("code").asString());
        assertFalse(response.path("success").asBoolean());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Long.class).longValue());
        assertNull(fixture.executions.account(AdmissionFixture.owner("bob-id"), "bob-budget").block());
    }

    @Test
    public void foreignAndUnknownBudgetsReturnIdenticalNotFoundResponses() throws Exception {
        fixture.initializeBudget("bob");
        assertEquals(send(query("missing"), 404), send(query("bob-budget"), 404));
        login("bob");
        assertEquals(send(query("missing"), 404), send(query("alice-budget"), 404));
        assertEquals("bob-budget", send(query("bob-budget"), 200).path("data").path("budgetRef").asString());
    }

    private NewAiProperties changeGrants(UnaryOperator<NewAiProperties.Grant> change) {
        var original = fixture.properties;
        return new NewAiProperties(original.enabled(), original.dataSourceBean(), original.releaseRef(), original.persistence(), original.limits(),
                original.grants().stream().map(change).toList(), original.capabilities(), original.bindings(), original.connections(), original.rates(), original.budgets());
    }

    @Test
    public void authenticationSpaceAndCurrentBudgetQualificationAreRequired() throws Exception {
        SecurityContextHolder.clearContext();
        send(query("alice-budget"), 401);
        login("alice");
        var request = query("alice-budget");
        request.put("scope", Map.of("tenantId", "other", "workspaceId", "workspace"));
        send(request, 403);
        request.put("scope", Map.of("tenantId", "tenant", "workspaceId", "other"));
        send(request, 403);
        configureMvc(changeGrants(grant -> new NewAiProperties.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(),
                grant.tenantId(), grant.workspaceId(), grant.grantRef(), grant.enabled(), grant.scopes(), grant.bindingIds(), Set.of())));
        assertEquals(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE.getCode(), send(query("alice-budget"), 404).path("code").asString());
    }

    @Test
    public void onlyReadScopeIsRequiredAndBudgetAdminAloneDoesNotGrantRead() throws Exception {
        for (var scopes : List.of(Set.of(AdmissionAuthorization.READ), Set.of(AdmissionAuthorization.BUDGET_ADMIN))) {
            configureMvc(changeGrants(grant -> new NewAiProperties.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(),
                    grant.tenantId(), grant.workspaceId(), grant.grantRef(), grant.enabled(), scopes, grant.bindingIds(), grant.budgetRefs())));
            send(query("alice-budget"), scopes.contains(AdmissionAuthorization.READ) ? 200 : 403);
        }
    }

    @Test
    public void invalidParametersAndMalformedJsonAreBadRequests() throws Exception {
        var original = query("alice-budget");
        for (var field : original.keySet()) {
            var invalid = new LinkedHashMap<>(original);
            invalid.put(field, null);
            send(invalid, 400);
        }
        for (var ref : List.of(" ", "a".repeat(257))) {
            send(query(ref), 400);
        }
        var invalid = query("alice-budget");
        invalid.put("scope", Map.of("tenantId", " ", "workspaceId", "workspace"));
        send(invalid, 400);
        finish(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{")).andReturn(), 400);
        finish(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)).andReturn(), 400);
    }

    @Test
    public void ledgerConfigurationConflictIsReportedWithoutRepairingTheAccount() throws Exception {
        var original = account();
        var changed = List.of(
                new BudgetCommands.Account(original.budgetRef(), original.owner(), AdmissionFixture.money("99"), original.held(), original.charged(), original.rateVersion(), 1),
                new BudgetCommands.Account(original.budgetRef(), original.owner(), original.limit(), original.held(), original.charged(), new DefinitionRef("rate", "text", "v2"), 1),
                new BudgetCommands.Account(original.budgetRef(), original.owner(), new Money(original.limit().amount(), Currency.getInstance("USD")),
                        new Money(original.held().amount(), Currency.getInstance("USD")), new Money(original.charged().amount(), Currency.getInstance("USD")), original.rateVersion(), 1));
        for (var account : changed) {
            jdbc.update("UPDATE arte_ai_account SET snapshot=? WHERE id_key=?", fixture.codec.encode(account), CanonicalJson.key("alice-budget"));
            var failure = send(query("alice-budget"), 400);
            assertEquals(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT.getCode(), failure.path("code").asString());
            assertEquals(account, account());
        }
    }

    @Test
    public void databaseFailureIsNotTreatedAsMissingAccountAndDoesNotLeakSql() throws Exception {
        jdbc.execute("DROP TABLE arte_ai_account");
        var failure = send(query("alice-budget"), 500);
        assertFalse(failure.path("success").asBoolean());
        assertFalse(failure.toString().contains("SELECT"));
        assertFalse(failure.toString().contains("arte_ai_account"));
        assertNotEquals(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED.getCode(), failure.path("code").asString());
    }

    @Test
    public void identityIsCapturedBeforeAsyncQueryAndMethodDeclaresAuthentication() throws Exception {
        var pending = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(query("alice-budget")))).andReturn();
        SecurityContextHolder.clearContext();
        assertEquals("alice-budget", finish(pending, 200).path("data").path("budgetRef").asString());
        assertEquals("isAuthenticated()", NewAiBudgetController.class.getMethod("getBudget", BudgetRequests.Query.class, Locale.class)
                .getAnnotation(PreAuthorize.class).value());
    }

    @Test
    public void disabledFeatureRequiresNoDependencies() {
        for (var properties : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", "false"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flags", properties));
                context.register(NewAiBudgetController.class);
                context.refresh();
                assertTrue(context.getBeansOfType(NewAiBudgetController.class).isEmpty());
            }
        }
    }
}
