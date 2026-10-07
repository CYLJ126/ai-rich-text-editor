package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.gateway.DefaultModelGateway;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.GenerationJson;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekWire;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiConfigurationController;
import com.arte.ainew.web.request.ConfigurationRequests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 真实 MVC／授权／网关支持判断；没有数据源、账本、Worker 或可调用的模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
public class ConfigurationHttpIntegrationTest {
    private static final String PATH = "/ai-new/configuration/discoverChatOptions";
    private static final Map<String, Object> QUERY = Map.of("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"));
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.systemUTC();
    private final AtomicBoolean revoked = new AtomicBoolean();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private NewAiProperties properties;
    private FixedControlCatalog catalog;
    private ExecutionContextFactory factory;

    @Before
    public void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var original = AdmissionFixture.properties();
        var connection = original.connections().getFirst();
        properties = copy(original, original.grants(), original.capabilities(), original.bindings(),
                List.of(connection(connection, "deepseek", ChatCompletionsSseProtocolAdapter.DEFINITION, ConnectionDefinition.State.ENABLED)),
                original.rates(), original.budgets(), original.limits());
        configure(properties, true);
        login("alice");
    }

    @After
    public void cleanup() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    private void configure(NewAiProperties configuration, boolean gatewayAvailable) {
        var fixed = new FixedExecutionAuthorizationResolver(configuration);
        ExecutionAuthorizationResolver resolver = (name, tenant, workspace, scopes) -> revoked.get()
                ? Mono.empty() : fixed.resolve(name, tenant, workspace, scopes);
        var authorization = new AdmissionAuthorization(resolver, configuration, clock);
        catalog = new FixedControlCatalog(configuration, authorization, clock);
        factory = new ExecutionContextFactory(resolver, clock);
        var generation = new NewAiGenerationProperties(true, null, null, 0, 0, 0, null, 0, 0);
        var provider = new DeepSeekGenerationProviderAdapter(configuration.capabilities(), GenerationJson.mapper(generation.maxFrameBytes()));
        var protocol = new ChatCompletionsSseProtocolAdapter<DeepSeekWire.Request>(GenerationJson.mapper(generation.maxFrameBytes()), generation);
        // supportsTextChat 只查看适配器元数据；不需要连接运行时。
        var delegate = new DefaultModelGateway<>(catalog, catalog, catalog::resolveConnection, null, provider, protocol, clock);
        ModelGateway gateway = new ModelGateway() {
            @Override
            public boolean supportsTextChat(ResolvedBinding binding, ConnectionDefinition connection) {
                return delegate.supportsTextChat(binding, connection);
            }

            @Override
            public Flux<GenerationSignal> generate(GatewayCall<GenerationRequest> call) {
                throw new AssertionError("Discovery must never generate, reserve budget or contact a provider");
            }
        };
        var service = new ChatConfigurationQueryService(configuration, authorization, catalog, () -> gatewayAvailable ? gateway : null);
        mvc = standaloneSetup(new NewAiConfigurationController(service, new NewAiHttpContext(factory, configuration), configuration))
                .setControllerAdvice(new ConversationExceptionHandler()).setValidator(validator).setAsyncRequestTimeout(10000).build();
    }

    private static NewAiProperties copy(NewAiProperties original, List<NewAiProperties.Grant> grants,
                                        List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                                        List<ConnectionDefinition> connections, List<NewAiProperties.Rate> rates,
                                        List<NewAiProperties.Budget> budgets, NewAiProperties.Limits limits) {
        return new NewAiProperties(true, original.dataSourceBean(), original.releaseRef(), original.persistence(), limits,
                grants, capabilities, bindings, connections, rates, budgets);
    }

    private static ConnectionDefinition connection(ConnectionDefinition original, String provider, DefinitionRef protocol,
                                                   ConnectionDefinition.State state) {
        return new ConnectionDefinition(original.definition(), provider, protocol, original.endpoint(), original.credential(), state,
                original.connectTimeout(), original.responseTimeout(), original.maxResponseBytes());
    }

    private NewAiProperties grants(UnaryOperator<NewAiProperties.Grant> change) {
        return copy(properties, properties.grants().stream().map(change).toList(), properties.capabilities(), properties.bindings(),
                properties.connections(), properties.rates(), properties.budgets(), properties.limits());
    }

    private static NewAiProperties.Grant grant(NewAiProperties.Grant original, Set<String> scopes, Set<String> bindings, Set<String> budgets) {
        return new NewAiProperties.Grant(original.subjectName(), original.subjectId(), original.principalKind(), original.tenantId(),
                original.workspaceId(), original.grantRef(), original.enabled(), scopes, bindings, budgets);
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
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

    private JsonNode options() throws Exception {
        var response = send(QUERY, 200);
        assertTrue(response.path("success").asBoolean());
        return response.path("data").path("options");
    }

    @Test
    public void returnsExactReferencesCompatibleBudgetsAndLimitsWithoutAnyPersistenceOrGeneration() throws Exception {
        var values = options();
        assertEquals(1, values.size());
        var option = values.get(0);
        assertEquals(Set.of("displayName", "binding", "capability", "contextWindowTokens", "limits", "defaults", "budgetRefs"), option.propertyNames());
        assertEquals("test-model", option.path("displayName").asString());
        assertEquals(json.valueToTree(AdmissionFixture.BINDING), option.path("binding"));
        assertEquals(json.valueToTree(AdmissionFixture.CAP), option.path("capability"));
        assertEquals(json.valueToTree(List.of("alice-budget")), option.path("budgetRefs"));
        assertEquals(1024, option.path("contextWindowTokens").asLong());
        assertEquals(1023, option.path("limits").path("maxInputTokens").asLong());
        assertEquals(256, option.path("limits").path("maxOutputTokens").asInt());
        assertEquals(2048, option.path("limits").path("maxInputBytes").asInt());
        assertEquals(4096, option.path("limits").path("maxOutputBytes").asLong());
        assertEquals(120, option.path("limits").path("maxTimeoutSeconds").asLong());
        assertEquals(768, option.path("defaults").path("maxInputTokens").asLong());
        assertEquals(256, option.path("defaults").path("maxOutputTokens").asInt());
        assertEquals(60, option.path("defaults").path("timeoutSeconds").asLong());
        var body = values.toString();
        for (var secret : List.of("example.invalid", "test-credential", "credential", "connection", "grant", "owner", "rate", "release-v1")) {
            assertFalse(body, body.contains(secret));
        }
        login("bob");
        assertEquals(json.valueToTree(List.of("bob-budget")), options().get(0).path("budgetRefs"));
    }

    @Test
    public void requiresAuthenticationAuthorizedScopeAndInvokePermission() throws Exception {
        SecurityContextHolder.clearContext();
        send(QUERY, 401);
        login("alice");
        for (var scope : List.of(Map.of("tenantId", "other", "workspaceId", "workspace"),
                Map.of("tenantId", "tenant", "workspaceId", "other"))) {
            send(Map.of("scope", scope), 403);
        }
        for (var scopes : List.of(Set.of(AdmissionAuthorization.READ), Set.of(AdmissionAuthorization.BUDGET_ADMIN), Set.of(AdmissionAuthorization.INVOKE))) {
            configure(grants(g -> grant(g, scopes, g.bindingIds(), g.budgetRefs())), true);
            send(QUERY, scopes.contains(AdmissionAuthorization.INVOKE) ? 200 : 403);
        }
    }

    @Test
    public void malformedOrMissingScopeIsBadRequest() throws Exception {
        for (var query : List.of(Map.<String, Object>of(), Collections.singletonMap("scope", null),
                Map.<String, Object>of("scope", Map.of("tenantId", " ", "workspaceId", "workspace")),
                Map.<String, Object>of("scope", Map.of("tenantId", "tenant", "workspaceId", "x".repeat(257))))) {
            send(query, 400);
        }
        finish(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{")).andReturn(), 400);
        finish(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)).andReturn(), 400);
    }

    @Test
    public void noPermittedBindingOrBudgetIsSuccessfulEmptyList() throws Exception {
        configure(grants(g -> grant(g, g.scopes(), Set.of(), g.budgetRefs())), true);
        assertEquals(0, options().size());
        configure(grants(g -> grant(g, g.scopes(), g.bindingIds(), Set.of())), true);
        assertEquals(0, options().size());
    }

    @Test
    public void missingGatewayAndUnsupportedProviderOrProtocolAreNotAdvertised() throws Exception {
        configure(properties, false);
        assertEquals(0, options().size());
        var original = properties.connections().getFirst();
        for (var connection : List.of(connection(original, "unknown", original.protocol(), original.state()),
                connection(original, "deepseek", new DefinitionRef("protocol", "unknown", "v1"), original.state()),
                connection(original, "deepseek", original.protocol(), ConnectionDefinition.State.DISABLED))) {
            configure(copy(properties, properties.grants(), properties.capabilities(), properties.bindings(), List.of(connection),
                    properties.rates(), properties.budgets(), properties.limits()), true);
            assertEquals(0, options().size());
        }
    }

    @Test
    public void disabledCapabilityAndNonStreamingCapabilityAreFiltered() throws Exception {
        var original = properties.capabilities().getFirst();
        for (var capability : List.of(new CapabilityDescriptor(original.definition(), original.kind(), original.inputSchema(), original.outputSchema(),
                        original.features(), original.sideEffect(), CapabilityDescriptor.Availability.DISABLED),
                new CapabilityDescriptor(original.definition(), original.kind(), original.inputSchema(), original.outputSchema(),
                        Set.of(CapabilityDescriptor.Feature.TEXT_INPUT), original.sideEffect(), original.availability()))) {
            var binding = properties.bindings().getFirst();
            configure(copy(properties, properties.grants(), List.of(capability), List.of(new ResolvedBinding(binding.definition(), capability,
                            binding.connection(), binding.remoteOperation(), binding.contextWindowTokens(), binding.rate())), properties.connections(),
                    properties.rates(), properties.budgets(), properties.limits()), true);
            assertEquals(0, options().size());
        }
    }

    @Test
    public void keepsDifferentBindingsAndVersionsForSameCapabilityInStableOrder() throws Exception {
        var original = properties.bindings().getFirst();
        var alternate = new ResolvedBinding(new DefinitionRef("binding", "alternate", "v1"), original.capability(), original.connection(),
                "another-model", 65536L, original.rate());
        var nextVersion = new ResolvedBinding(new DefinitionRef("binding", "text", "v2"), original.capability(), original.connection(),
                original.remoteOperation(), original.contextWindowTokens(), original.rate());
        var grants = properties.grants().stream().map(g -> grant(g, g.scopes(), Set.of("text", "alternate"), g.budgetRefs())).toList();
        configure(copy(properties, grants, properties.capabilities(), List.of(nextVersion, original, alternate), properties.connections(),
                properties.rates(), properties.budgets(), properties.limits()), true);
        var values = options();
        assertEquals(3, values.size());
        assertEquals(json.valueToTree(alternate.definition()), values.get(0).path("binding"));
        assertEquals(json.valueToTree(original.definition()), values.get(1).path("binding"));
        assertEquals(json.valueToTree(nextVersion.definition()), values.get(2).path("binding"));
        assertEquals(32768, values.get(0).path("defaults").path("maxInputTokens").asLong());
    }

    @Test
    public void excludesDifferentRateVersionBudgetsAndKeepsUninitializedMatchingBudgetsSorted() throws Exception {
        var otherRate = new NewAiProperties.Rate(new DefinitionRef("rate", "text", "v2"), AdmissionFixture.money("1"), AdmissionFixture.money("2"));
        var budgets = new ArrayList<>(properties.budgets());
        budgets.add(new NewAiProperties.Budget("wrong-rate", AdmissionFixture.owner("alice-id"), AdmissionFixture.money("100"), otherRate.definition()));
        budgets.add(new NewAiProperties.Budget("a-matching", AdmissionFixture.owner("alice-id"), AdmissionFixture.money("100"), AdmissionFixture.RATE));
        var grants = properties.grants().stream().map(g -> g.subjectName().equals("alice")
                ? grant(g, g.scopes(), g.bindingIds(), Set.of("wrong-rate", "a-matching", "alice-budget")) : g).toList();
        configure(copy(properties, grants, properties.capabilities(), properties.bindings(), properties.connections(),
                List.of(properties.rates().getFirst(), otherRate), budgets, properties.limits()), true);
        assertEquals(json.valueToTree(List.of("a-matching", "alice-budget")), options().get(0).path("budgetRefs"));
        var mismatched = budgets.stream().map(b -> b.budgetRef().equals("alice-budget")
                ? new NewAiProperties.Budget(b.budgetRef(), b.owner(), b.limit(), otherRate.definition()) : b).toList();
        configure(copy(properties, properties.grants(), properties.capabilities(), properties.bindings(), properties.connections(),
                List.of(properties.rates().getFirst(), otherRate), mismatched, properties.limits()), true);
        assertEquals(0, options().size());
    }

    @Test
    public void defaultsFitTinyWindowAndShortTimeoutAndOneTokenWindowIsOmitted() throws Exception {
        var original = properties.bindings().getFirst();
        for (long window : List.of(1L, 2L, 128L)) {
            var binding = new ResolvedBinding(original.definition(), original.capability(), original.connection(), original.remoteOperation(), window, original.rate());
            var limits = new NewAiProperties.Limits(2048, 4096, 4096, Duration.ofMillis(1500), Duration.ofMinutes(15));
            configure(copy(properties, properties.grants(), properties.capabilities(), List.of(binding), properties.connections(),
                    properties.rates(), properties.budgets(), limits), true);
            var values = options();
            if (window == 1) {
                assertEquals(0, values.size());
            } else {
                var option = values.get(0);
                assertEquals(window - 1, option.path("limits").path("maxOutputTokens").asLong());
                assertEquals(1, option.path("defaults").path("timeoutSeconds").asLong());
                assertEquals(window, option.path("defaults").path("maxInputTokens").asLong()
                        + option.path("defaults").path("maxOutputTokens").asLong());
            }
        }
    }

    @Test
    public void capturesIdentityBeforeAsyncQueryAndDeclaresControllerAuthentication() throws Exception {
        var pending = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(QUERY))).andReturn();
        SecurityContextHolder.clearContext();
        assertEquals(json.valueToTree(List.of("alice-budget")), finish(pending, 200).path("data").path("options").get(0).path("budgetRefs"));
        var method = NewAiConfigurationController.class.getMethod("discoverChatOptions", ConfigurationRequests.DiscoverChatOptions.class, Locale.class);
        var authorization = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (authorization == null) {
            authorization = AnnotatedElementUtils.findMergedAnnotation(NewAiConfigurationController.class, PreAuthorize.class);
        }
        assertNotNull(authorization);
        assertEquals("isAuthenticated()", authorization.value());
    }

    @Test
    public void discoveryDoesNotAuthorizeUseAfterGrantIsRevoked() throws Exception {
        assertEquals(1, options().size());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var context = factory.create(authentication, new ExecutionContextRequest("tenant", "workspace", Set.of(AdmissionAuthorization.INVOKE),
                Duration.ofSeconds(30), null, null, properties.releaseRef(), null)).block();
        revoked.set(true);
        send(QUERY, 403);
        assertThrows(AccessDeniedException.class, () -> catalog.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context).block());
    }

    @Test
    public void disabledFeatureRequiresNoDependencies() {
        for (var flags : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", "false"))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flags", flags));
                context.register(NewAiConfigurationController.class);
                context.refresh();
                assertTrue(context.getBeansOfType(NewAiConfigurationController.class).isEmpty());
            }
        }
    }
}
