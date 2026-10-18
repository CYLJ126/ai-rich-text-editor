package com.arte.ainew.tool.discovery;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.application.tool.discovery.LocalToolDiscovery.Code.*;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 临时 H2 的真实 MyBatis 事务验证；不连接配置中的业务数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public class LocalToolDiscoveryPersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private Scheduler scheduler;
    private JdbcTemplate jdbc;
    private MybatisToolCatalogStore store;

    @Before
    public void setup() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        scheduler = Schedulers.newBoundedElastic(2, 16, "tool-discovery-sql-test");
        store = new MybatisToolCatalogStore(dataSource, new JacksonExecutionRecordCodec(), scheduler, Clock.systemUTC());
    }

    @After
    public void cleanup() {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
    }

    private LocalToolDiscovery service(LocalToolRegistry.Registration... registrations) {
        return new LocalToolDiscovery(new LocalToolRegistry(List.of(registrations)), store, new ToolSchemaValidator(), scheduler, Clock.systemUTC(), Duration.ofSeconds(2));
    }

    @Test
    public void twoNodesShareImmutableHistoryAndProduceTheSameContentManifest() {
        var fixture = fixture("text", "v1");
        var one = service(fixture.registration());
        var two = service(fixture.registration());
        assertEquals(APPLIED, one.refresh().block(WAIT).code());
        assertEquals(REPLAYED, two.refresh().block(WAIT).code());
        assertEquals(one.state().snapshot().checksum(), two.state().snapshot().checksum());
        assertEquals(fixture.revision(), store.findTool(fixture.revision().tool().definition()).block(WAIT));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_definition", Integer.class));
    }

    @Test
    public void restartedNodeCannotInstallDriftEvenWhenAnotherProviderIsNew() {
        var original = fixture("text", "v1");
        service(original.registration()).refresh().block(WAIT);
        var changed = new ToolDefinitionRevision(withTitle(original.revision().tool(), "Changed"), original.provider().definition(), original.revision().target());
        var newProvider = fixture("other", "v1");
        var restarted = service(original.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(original.schema()), List.of(changed)))), newProvider.registration());
        assertEquals(CONTRACT_DRIFT, restarted.refresh().block(WAIT).code());
        assertNull(restarted.state().snapshot());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_provider", Integer.class));
        assertNull(store.findProvider(newProvider.provider().definition()).block(WAIT));
        assertEquals(original.revision(), store.findTool(original.revision().tool().definition()).block(WAIT));
    }

    @Test
    public void sqlFailureRollsBackNewArtifactsAndKeepsThePreviousNodeView() {
        var original = fixture("text", "v1");
        var definitions = new AtomicReference<>(original.definitions());
        var discovery = service(original.registration(() -> Mono.just(definitions.get())));
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        var extraSchema = schema(ref("schema", "extra", "v1"), original.schema().document());
        definitions.set(new LocalToolDefinitionSource.Definitions(List.of(original.schema(), extraSchema), List.of(original.revision())));
        jdbc.execute("ALTER TABLE arte_ai_tool_schema ADD CONSTRAINT injected_failure CHECK (id_key <> '" +
                com.arte.ainew.serialization.CanonicalJson.key("schema", "extra", "v1") + "')");
        assertEquals(STORE_FAILED, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        assertFalse(discovery.state().discoveryHealthy());
        assertNull(store.findSchema(extraSchema.definition()).block(WAIT));
        jdbc.execute("ALTER TABLE arte_ai_tool_schema DROP CONSTRAINT injected_failure");
        assertEquals(APPLIED, discovery.refresh().block(WAIT).code());
        assertNotNull(store.findSchema(extraSchema.definition()).block(WAIT));
    }
}
