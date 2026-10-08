package com.arte.ai.service;

import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.mapper.ModelConfigMapper;
import com.arte.ai.api.AssistantService;
import com.arte.ai.api.ConversationService;
import com.arte.ai.pojo.assistant.AssistantDto;
import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelConfigParam;
import com.arte.ai.utils.RequestParamHandler;
import com.arte.ai.pojo.chat.ChatRequestParam;
import com.arte.ai.web.controller.ModelConfigController;
import com.arte.core.enums.StatusEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.exception.ChatException;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Real mapper/SQL tests for sharing, authorization and default selection. No provider calls. */
public class ModelConfigServiceTest {
    private SqlSession session;
    private ModelConfigMapper mapper;
    private ModelConfigServiceImpl service;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             InputStream input = getClass().getResourceAsStream("/db/model-config.sql")) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";\\s*\\n")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.addMapper(ModelConfigMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(ModelConfigMapper.class);
        service = new ModelConfigServiceImpl() {
            @Override
            public void evictDefaultModelConfig(String userName) {}
        };
        Class<?> parent = service.getClass();
        while (parent != null) {
            try {
                Field field = parent.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(service, mapper);
                break;
            } catch (NoSuchFieldException ignored) {
                parent = parent.getSuperclass();
            }
        }
        login("owner");
    }

    @After
    public void tearDown() {
        UserContext.clear();
        if (session != null) session.close();
    }

    @Test
    public void publicModelsAllowUsersOrActiveRolesButPrivateAndDisabledModelsDoNot() {
        ModelConfigDto restricted = create("restricted", true, 0, List.of("alice"), List.of("reader", "disabled"));
        ModelConfigDto privateModel = create("private", false, 0, List.of(), List.of());
        ModelConfigDto disabled = create("disabled", true, 0, List.of(), List.of());
        service.updateModelConfig(change(disabled.getId(), dto -> dto.setStatus(StatusEnum.CLOSED)));
        assertTrue(service.isAccessibleModel(restricted.getId(), "alice"));
        assertTrue(service.isAccessibleModel(restricted.getId(), "bob"));
        assertTrue(service.isAccessibleModel(restricted.getId(), "owner"));
        assertFalse(service.isAccessibleModel(restricted.getId(), "eve"));
        assertFalse(service.isAccessibleModel(restricted.getId(), "stranger"));
        assertFalse(service.isAccessibleModel(restricted.getId(), "' OR 1=1 --"));
        assertFalse(service.isAccessibleModel(restricted.getId(), ""));
        assertFalse(service.isAccessibleModel(privateModel.getId(), "alice"));
        assertFalse(service.isAccessibleModel(disabled.getId(), "owner"));
        login("alice");
        ModelConfigParam query = new ModelConfigParam();
        query.setCreateBy("owner"); // client cannot widen or replace the access scope
        assertEquals(List.of(restricted.getId()), service.listModelConfigs(query).getRecords()
                .stream().map(ModelConfigDto::getId).toList());
    }

    @Test
    public void removingRoleMembershipImmediatelyRemovesModelAccess() throws Exception {
        ModelConfigDto model = create("role-only", true, 0, List.of(), List.of("reader"));
        assertEquals(model.getId(), service.getDefaultModelConfig("bob").getId());
        try (var statement = session.getConnection().createStatement()) {
            statement.execute("DELETE FROM arte_rbac_relation WHERE source = 'bob'");
        }
        session.clearCache();
        assertFalse(service.isAccessibleModel(model.getId(), "bob"));
        assertNull(service.getDefaultModelConfig("bob"));
    }

    @Test
    public void onlyCreatorCanEditWithdrawDeleteOrChangeModelFlags() throws Exception {
        ModelConfigDto model = create("public", true, 0, List.of(), List.of());
        login("alice");
        assertThrows(BusinessException.class, () -> service.updateModelConfig(
                change(model.getId(), dto -> dto.setPublicFlag(false))));
        assertThrows(BusinessException.class, () -> service.deleteModelConfig(model.getId()));
        ModelConfigController controller = new ModelConfigController();
        inject(controller, "modelConfigService", service);
        ModelConfigParam param = new ModelConfigParam();
        param.setId(model.getId());
        param.setPinFlag(true);
        param.setStatus(StatusEnum.CLOSED);
        assertThrows(BusinessException.class, () -> controller.toggleModelConfigPin(param));
        assertThrows(BusinessException.class, () -> controller.toggleModelConfigStatus(param));
        assertThrows(BusinessException.class, () -> controller.setAsDefaultModelConfig(param));
        login("owner");
        service.updateModelConfig(change(model.getId(), dto -> dto.setPublicFlag(false)));
        assertNull(service.getAccessibleModel(model.getId(), "alice"));
        assertTrue(service.isAccessibleModel(model.getId(), "owner"));
        assertTrue(service.deleteModelConfig(model.getId()));
    }

    @Test
    public void defaultFallsBackToFirstAuthorizedPublicModelOnlyWithoutOwnedModels() {
        create("restricted", true, -1, List.of("bob"), List.of());
        ModelConfigDto first = create("first", true, 0, List.of(), List.of());
        ModelConfigDto second = create("second", true, 5, List.of(), List.of());
        assertEquals(first.getId(), service.getDefaultModelConfig("alice").getId());
        service.updateModelConfig(change(first.getId(), dto -> dto.setPublicFlag(false)));
        assertEquals(second.getId(), service.getDefaultModelConfig("alice").getId());
        login("alice");
        ModelConfigDto own = create("own", false, 100, List.of(), List.of());
        assertEquals(own.getId(), service.getDefaultModelConfig("alice").getId());
        service.updateModelConfig(change(own.getId(), dto -> dto.setStatus(StatusEnum.CLOSED)));
        assertNull(service.getDefaultModelConfig("alice"));
        assertNull(service.getDefaultModelConfig(""));
    }

    @Test
    public void grantsCanBeChangedAndClearedAndCreatorCannotBeForged() {
        ModelConfigDto model = create("shared", true, 0, List.of("alice", "alice"), List.of());
        assertEquals("owner", model.getCreateBy());
        assertEquals(List.of("alice"), service.getOwnedModel(model.getId(), "owner").getAllowedUsers());
        ModelConfigDto changes = change(model.getId(), dto -> dto.setAllowedUsers(List.of("bob")));
        changes.setCreateBy("alice");
        changes.setApiKey("********");
        service.updateModelConfig(changes);
        assertNull(service.getAccessibleModel(model.getId(), "alice"));
        assertNotNull(service.getAccessibleModel(model.getId(), "bob"));
        assertEquals("owner", service.getById(model.getId()).getCreateBy());
        assertEquals("encrypted-test-key", service.getById(model.getId()).getApiKey());
        service.updateModelConfig(change(model.getId(), dto -> dto.setAllowedUsers(List.of()).setAllowedRoles(List.of())));
        assertNotNull(service.getAccessibleModel(model.getId(), "stranger"));
    }

    @Test
    public void revokedModelsAreRejectedBeforeGenerationAndResponsesNeverMutateStoredCredentials() throws Exception {
        ModelConfigDto model = create("shared", true, 0, List.of(), List.of());
        ModelConfigController controller = new ModelConfigController();
        inject(controller, "modelConfigService", service);
        login("alice");
        ModelConfigDto view = controller.getModelConfig(new ModelConfigParam().setDefaultFlag(true)).getData();
        assertEquals("********", view.getApiKey());
        assertFalse(view.getManageable());
        assertTrue(view.getDefaultFlag());
        assertNull(view.getAllowedUsers());
        assertEquals("encrypted-test-key", service.getDefaultModelConfig("alice").getApiKey());
        RequestParamHandler handler = new RequestParamHandler();
        inject(handler, "modelConfigService", service);
        login("owner");
        service.updateModelConfig(change(model.getId(), dto -> dto.setPublicFlag(false)));
        ChatRequestParam request = new ChatRequestParam().setModelId(model.getId()).setUserName("alice")
                .setUserMessageId("user-msg").setAssistantMessageId("assistant-msg");
        assertThrows(ChatException.class, () -> handler.handleGenerateRequest(request));
    }

    @Test
    public void conversationAndAssistantModelsCannotBypassAuthorization() throws Exception {
        ModelConfigDto privateModel = create("private", false, 0, List.of(), List.of());
        RequestParamHandler handler = new RequestParamHandler();
        inject(handler, "modelConfigService", service);
        ConversationDto conversation = new ConversationDto();
        conversation.setModelId(privateModel.getId());
        AssistantDto assistant = new AssistantDto();
        assistant.setModelId(privateModel.getId());
        inject(handler, "conversationService", proxy(ConversationService.class, conversation));
        inject(handler, "assistantService", proxy(AssistantService.class, assistant));
        ChatRequestParam request = new ChatRequestParam().setUserName("alice").setConvId("conversation")
                .setUserMessageId("user-msg").setAssistantMessageId("assistant-msg");
        assertThrows(ChatException.class, () -> handler.handleChatRequest(request));
        conversation.setModelId(null);
        conversation.setAssistantId(1);
        assertThrows(ChatException.class, () -> handler.handleChatRequest(request));
        request.setModelId(privateModel.getId());
        assertThrows(ChatException.class, () -> handler.handleChatRequest(request));
    }

    @Test
    public void generationWithoutSelectedModelUsesTheAuthorizedPublicDefault() throws Exception {
        ModelConfigDto model = create("public", true, 0, List.of(), List.of());
        RequestParamHandler handler = new RequestParamHandler();
        inject(handler, "modelConfigService", service);
        inject(handler, "assistantService", proxy(AssistantService.class, null));
        ChatRequestParam request = new ChatRequestParam().setUserName("alice")
                .setUserMessageId("user-msg").setAssistantMessageId("assistant-msg");
        assertEquals(model.getId(), handler.handleGenerateRequest(request).getModelAutoId());
    }

    private ModelConfigDto create(String modelId, boolean publicFlag, int order, List<String> users, List<String> roles) {
        ModelConfigDto dto = new ModelConfigDto();
        dto.setProvider(ModelProviderEnum.OPENAI);
        dto.setModelId(modelId);
        dto.setModelName(modelId);
        dto.setApiKey("encrypted-test-key");
        dto.setPublicFlag(publicFlag);
        dto.setAllowedUsers(users);
        dto.setAllowedRoles(roles);
        dto.setStatus(StatusEnum.DOING);
        dto.setSortOrder(order);
        dto.setCreateBy("forged-owner");
        return service.addModelConfig(dto);
    }

    private static ModelConfigDto change(Integer id, java.util.function.Consumer<ModelConfigDto> configure) {
        ModelConfigDto dto = new ModelConfigDto();
        dto.setId(id);
        configure.accept(dto);
        return dto;
    }

    private static void login(String userName) {
        UserContext.setUserOnlineInfo(new UserOnlineInfo().setUserName(userName));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T proxy(Class<T> type, Object response) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> response));
    }
}
