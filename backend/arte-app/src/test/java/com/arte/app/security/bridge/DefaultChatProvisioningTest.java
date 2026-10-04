package com.arte.app.security.bridge;

import com.arte.app.ainew.JdbcModelExecutionStore;
import com.arte.app.ainew.NewChatBootstrapService;
import com.arte.app.ainew.NewModelConfiguration;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultChatProvisioningTest extends SecurityBridgeFixture {
    @BeforeEach
    void modelSchema() throws Exception {
        execute("arte-ai-new-model-ddl-mysql.sql");
        jdbc.update("DELETE FROM arte_security_member");
    }

    void execute(String name) throws Exception {
        try (var connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts", name))));
        }
    }

    void ragPermissions(Integer articleId) throws Exception {
        String script = Files.readString(Path.of("scripts/arte-ai-new-rag-permissions-mysql.sql"));
        if (articleId != null) script = script.replace("set @rag_article_id = NULL;", "set @rag_article_id = " + articleId + ";");
        try (var connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(script));
        }
    }

    @Test
    void ragScriptAddsReadPolicyButOnlyGrantsTheExplicitlySelectedOwnedArticle() throws Exception {
        defaultUser("alice");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        ragPermissions(null);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
        ragPermissions(10);
        ragPermissions(10);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant WHERE resource_id='11'", Integer.class));
        var context = contexts.create(http, TENANT, WORKSPACE, "ai-new-model", "default-model",
                java.util.Set.of("resource.read", "resource.ai_process", "resource.egress"));
        assertTrue(repository.resourceGrant(ResourceRef.current("ARTICLE", "10"), context.scope().principal(), "resource.ai_process").orElseThrow().enabled());
        assertTrue(repository.resourceGrant(ResourceRef.current("ARTICLE", "10"), context.scope().principal(), "resource.egress").orElseThrow().enabled());
    }

    @Test
    void ragScriptRetainsRevocationsAndCannotGrantDeletedForeignOrNonOwnedArticles() throws Exception {
        defaultUser("alice");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        ragPermissions(10);
        jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE, revision=2 WHERE resource_id='10'");
        ragPermissions(10);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant WHERE enabled=TRUE", Integer.class));
        jdbc.update("UPDATE arte_rt_article SET create_by='bob' WHERE id=11");
        ragPermissions(11);
        jdbc.update("UPDATE arte_rt_article SET create_by='alice',is_delete=1 WHERE id=11");
        ragPermissions(11);
        jdbc.update("UPDATE arte_rt_article SET is_delete=0 WHERE id=11");
        jdbc.update("UPDATE arte_security_resource SET workspace_id='other' WHERE resource_id='11' AND resource_type='ARTICLE'");
        ragPermissions(11);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled=FALSE WHERE action_code='resource.read'");
        ragPermissions(10);
        assertFalse(jdbc.queryForObject("SELECT binding_enabled FROM arte_security_application_policy WHERE action_code='resource.read'", Boolean.class));
    }

    @Test
    void ragScriptCannotGrantWithoutAnActiveMemberAndAiApplication() throws Exception {
        defaultUser("alice");
        ragPermissions(10);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled=FALSE WHERE action_code='resource.ai_process'");
        ragPermissions(10);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_resource_grant", Integer.class));
    }

    void defaultUser(String name) {
        jdbc.update("UPDATE arte_rbac_user SET user_name=? WHERE id=1", name);
        login(name, 1);
    }

    NewChatBootstrapService bootstrap() {
        var definitions = new NewModelConfiguration().newModelDefinitions(TENANT, WORKSPACE, "v1",
                URI.create("https://api.deepseek.com/chat/completions"), "ARTE_NEW_MODEL_API_KEY");
        return new NewChatBootstrapService(identity, repository, definitions, "ai-new-model", "deepseek-flash", 8192, 2048);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "zhangsc"})
    void scriptMatchesUserIdAndRegistersAnAvailableSpaceWithMatchingConnectionAndBudgetKeys(String name) throws Exception {
        defaultUser(name);
        assertEquals("NO_ACCESS", bootstrap().read(http).unavailableReason());
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        var result = bootstrap().read(http);
        assertNull(result.unavailableReason());
        assertEquals(WORKSPACE, result.workspaces().getFirst().workspaceId());
        assertEquals(List.of("resource.ai_process", "resource.egress"), result.workspaces().getFirst().allowedActions());
        assertEquals("https://api.deepseek.com/chat/completions", result.defaultModel().destination());
        var connection = ResourceRef.saved("ai-connection", "default-model", "v1");
        var context = contexts.create(http, TENANT, WORKSPACE, "ai-new-model", "default-model",
                java.util.Set.of("resource.ai_process", "resource.egress"));
        assertEquals("https://api.deepseek.com", repository.connection(context, connection).orElseThrow().origin());
        assertTrue(repository.egressRule(context, repository.task(context).orElseThrow(), connection, "model.generate").orElseThrow().enabled());
        String key = JdbcModelExecutionStore.budgetKey(new ExecutionScope(TENANT, WORKSPACE, ALICE));
        assertEquals("CNY", jdbc.queryForObject("SELECT currency FROM arte_ai_new_budget WHERE scope_key=?", String.class, key));
        assertEquals(new BigDecimal("10.00000000"), jdbc.queryForObject("SELECT amount_limit FROM arte_ai_new_budget WHERE scope_key=?", BigDecimal.class, key));
    }

    @Test
    void rerunningDoesNotDuplicateGrantsRestoreRevocationsOrResetBudgetUsage() throws Exception {
        defaultUser("zhangsc");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        jdbc.update("UPDATE arte_security_member SET enabled=FALSE");
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled=FALSE WHERE action_code='resource.ai_process'");
        jdbc.update("UPDATE arte_ai_new_budget SET reserved_amount=1, spent_amount=2, amount_limit=20, enabled=FALSE");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_member", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_connection", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_egress_rule", Integer.class));
        assertEquals("NO_ACCESS", bootstrap().read(http).unavailableReason());
        assertEquals(new BigDecimal("1.00000000"), jdbc.queryForObject("SELECT reserved_amount FROM arte_ai_new_budget", BigDecimal.class));
        assertEquals(new BigDecimal("2.00000000"), jdbc.queryForObject("SELECT spent_amount FROM arte_ai_new_budget", BigDecimal.class));
        assertEquals(new BigDecimal("20.00000000"), jdbc.queryForObject("SELECT amount_limit FROM arte_ai_new_budget", BigDecimal.class));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM arte_ai_new_budget", Boolean.class));
    }

    @Test
    void scriptDoesNotGrantTheDefaultsToADifferentUserId() throws Exception {
        jdbc.update("DELETE FROM arte_rbac_user WHERE id=1");
        jdbc.update("UPDATE arte_rbac_user SET user_name='zhangsc' WHERE id=2");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_budget", Integer.class));
    }

    @Test
    void scriptDoesNotGrantTheDefaultsToADisabledAccount() throws Exception {
        defaultUser("zhangsc");
        jdbc.update("UPDATE arte_rbac_user SET status='3' WHERE id=1");
        execute("arte-ai-new-deepseek-admin-dml-mysql.sql");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_application_policy", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_budget", Integer.class));
    }
}
