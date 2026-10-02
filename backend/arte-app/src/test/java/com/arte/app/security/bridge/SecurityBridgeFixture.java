package com.arte.app.security.bridge;

import com.arte.app.api.rbac.TokenService;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.*;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

abstract class SecurityBridgeFixture {
    static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    static final String TENANT = "personal-1", WORKSPACE = "workspace-1";
    static final PrincipalRef ALICE = new PrincipalRef("1", PrincipalType.USER);
    static final ResourceRef ARTICLE = ResourceRef.saved("ARTICLE", "10", "v1");
    static final ResourceRef CONNECTION = ResourceRef.saved("ai-connection", "model", "v1");
    JdbcTemplate jdbc;
    JdbcDataSource datasource;
    org.apache.ibatis.session.SqlSessionFactory mybatis;
    JdbcSecurityRepository repository;
    TokenStub tokens;
    ExistingIdentityAdapter identity;
    ExecutionContextFactory contexts;
    ExistingAuthorizationService authorization;
    ExistingEgressPolicy egress;
    EgressConsentService consents;
    MockHttpServletRequest http;
    MutableClock clock;

    @BeforeEach
    void setup() throws Exception {
        datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(datasource);
        String schema = Files.readString(Path.of("scripts/arte-security-bridge-ddl-mysql.sql"))
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        try (var connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
        jdbc.execute("CREATE TABLE arte_rbac_user(id INT PRIMARY KEY, user_name VARCHAR(64) UNIQUE, status CHAR, row_version INT)");
        jdbc.execute("CREATE TABLE arte_rbac_role(id INT PRIMARY KEY, role_code VARCHAR(64), status CHAR)");
        jdbc.execute("CREATE TABLE arte_rbac_relation(binding_type VARCHAR(32), source VARCHAR(64), target VARCHAR(64))");
        jdbc.execute("CREATE TABLE arte_rt_catalog(id INT PRIMARY KEY, father_id INT, create_by VARCHAR(64), is_public BOOLEAN, is_delete INT)");
        jdbc.execute("CREATE TABLE arte_rt_article(id INT PRIMARY KEY, catalog_id INT, create_by VARCHAR(64), is_public BOOLEAN, is_delete INT)");
        jdbc.execute("CREATE TABLE arte_rt_share(id INT PRIMARY KEY, resource_type VARCHAR(32), resource_id INT, target_type VARCHAR(16), target_user VARCHAR(64), target_role VARCHAR(64), permission VARCHAR(32), article_permission VARCHAR(32), create_by VARCHAR(64), create_time TIMESTAMP)");
        jdbc.update("INSERT INTO arte_rbac_user VALUES (1, 'alice', '1', 1), (2, 'bob', '1', 1)");
        jdbc.update("INSERT INTO arte_rt_catalog VALUES (100, NULL, 'alice', FALSE, 0), (101, 100, 'alice', FALSE, 0)");
        jdbc.update("INSERT INTO arte_rt_article VALUES (10, 101, 'alice', FALSE, 0), (11, 101, 'alice', FALSE, 0)");
        jdbc.update("INSERT INTO arte_security_member VALUES (?, ?, 1, TRUE, 1)", TENANT, WORKSPACE);
        jdbc.update("INSERT INTO arte_security_resource VALUES ('ARTICLE', '10', ?, ?, TRUE, 1), ('ARTICLE', '11', ?, ?, TRUE, 1), ('CATALOG', '100', ?, ?, TRUE, 1)", TENANT, WORKSPACE, TENANT, WORKSPACE, TENANT, WORKSPACE);
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        try (var xml = getClass().getResourceAsStream("/com/arte/app/mapper/richtext/ShareMapper.xml")) {
            // H2 要求递归 CTE 显式列名；只补等价列名，不改生产 Mapper 或分享条件。
            String adapted = new String(xml.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("WITH RECURSIVE ancestors AS (", "WITH RECURSIVE ancestors (id, father_id) AS (");
            new XMLMapperBuilder(new ByteArrayInputStream(adapted.getBytes(StandardCharsets.UTF_8)), config,
                    "ShareMapper.xml", config.getSqlFragments()).parse();
        }
        mybatis = new SqlSessionFactoryBuilder().build(config);
        repository = new JdbcSecurityRepository(jdbc);
        clock = new MutableClock(NOW);
        tokens = new TokenStub();
        http = new MockHttpServletRequest();
        login("alice", 1);
        identity = new ExistingIdentityAdapter(tokens, repository);
        contexts = new ExecutionContextFactory(identity, repository, clock, Duration.ofMinutes(30));
        authorization = new ExistingAuthorizationService(repository, new LegacyResourcePermissions(jdbc,
                new ExistingShareQueries(jdbc, mybatis), repository), clock);
        egress = new ExistingEgressPolicy(repository, authorization, clock);
        consents = new EgressConsentService(identity, repository, egress, clock);
    }

    void login(String name, int id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
        var online = new UserOnlineInfo().setId(id).setUserName(name).setStatus(StatusEnum.DOING);
        tokens.details = new JwtUserDto(online);
    }

    ExecutionContext context(CommonResourceAction... actions) {
        var codes = Arrays.stream(actions).map(CommonResourceAction::code).collect(Collectors.toSet());
        for (String code : codes) policy(code);
        return contexts.create(http, TENANT, WORKSPACE, "new-ai", "chat", codes,
                Map.of(ARTICLE, codes, ResourceRef.saved("ARTICLE", "11", "v1"), codes,
                        ResourceRef.current("CATALOG", "100"), codes));
    }

    void policy(String action) {
        jdbc.update("MERGE INTO arte_security_application_policy KEY(tenant_id, workspace_id, application_id, binding_id, action_code) VALUES (?, ?, 'new-ai', 'chat', ?, TRUE, TRUE, 1)", TENANT, WORKSPACE, action);
    }

    void grant(String resourceId, int userId, CommonResourceAction action) {
        jdbc.update("INSERT INTO arte_security_resource_grant VALUES ('ARTICLE', ?, ?, ?, TRUE, 1)", resourceId, userId, action.code());
    }

    AuthorizationDecision check(ExecutionContext context, ResourceRef resource, CommonResourceAction action) {
        return authorization.requireAuthorized(AuthorizationRequest.of(context, resource, action), clock);
    }

    EgressRequest prepared(ExecutionContext context, List<SourceRef> sources) {
        String key = SecurityFingerprints.resource(CONNECTION);
        jdbc.update("MERGE INTO arte_security_connection KEY(tenant_id, workspace_id, connection_key) VALUES (?, ?, ?, 'https://provider.example', TRUE, 1)", TENANT, WORKSPACE, key);
        jdbc.update("MERGE INTO arte_security_egress_rule KEY(tenant_id, workspace_id, application_id, binding_id, connection_key, purpose) VALUES (?, ?, 'new-ai', 'chat', ?, 'inference', TRUE, 1)", TENANT, WORKSPACE, key);
        return EgressRequest.of(context, sources, new EgressDestination(CONNECTION, URI.create("https://provider.example")), "inference", "server-content-sha256", null);
    }

    EgressRequest confirmed(EgressRequest request) {
        ResourceRef consent = consents.confirm(http, request);
        return new EgressRequest(request.context(), request.executor(), request.sources(), request.destination(), request.purpose(), request.contentDigest(), consent);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        UserContext.clear();
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * 测试替身只替代 Redis／签名依赖；身份、成员、任务及权限记录均查询真实测试库。
     */
    static final class TokenStub implements TokenService {
        boolean valid = true;
        org.springframework.security.core.userdetails.UserDetails details;

        @Override
        public String getToken(jakarta.servlet.http.HttpServletRequest request) {
            return "test-session";
        }

        @Override
        public boolean verifyToken(String token) {
            return valid;
        }

        @Override
        public org.springframework.security.core.userdetails.UserDetails getUserDetailsByToken(String token) {
            return details;
        }

        @Override
        public void renewal(String token, jakarta.servlet.http.HttpServletRequest request) {
        }

        @Override
        public String createToken(org.springframework.security.core.Authentication authentication) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String decrypt(String encoded) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String encrypt(String decoded) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPublicKey() {
            throw new UnsupportedOperationException();
        }
    }
}
