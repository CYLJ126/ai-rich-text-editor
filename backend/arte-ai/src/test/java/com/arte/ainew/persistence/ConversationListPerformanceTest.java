package com.arte.ainew.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.serialization.CanonicalJson;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * H2 基线与 SQL 成本验证，不宣称等于生产 MySQL 延迟。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
public class ConversationListPerformanceTest {
    private static final ExecutionOwner OWNER = new ExecutionOwner("tenant", "workspace", "subject");

    @Test
    public void indexedListsStayBoundedAtOneAndTenThousandSessionsWithoutHistoryReads() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var queries = new CopyOnWriteArrayList<String>();
        var measured = new AbstractDataSource() {
            public Connection getConnection() throws SQLException {
                return wrap(ds.getConnection(), Connection.class, queries);
            }

            public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }
        };
        var scheduler = Schedulers.newBoundedElastic(2, 64, "list-perf");
        try {
            var store = new MybatisExecutionPersistence(measured, new JacksonExecutionRecordCodec(), scheduler);
            String owner = CanonicalJson.key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId());
            // 故意去掉历史与快照表：目录读取不能依赖它们，即使真实系统历史很大也保持同样 SQL 成本。
            jdbc.execute("DROP TABLE arte_ai_turn");
            jdbc.execute("DROP TABLE arte_ai_conversation_new");
            for (int size : List.of(1_000, 10_000))
                for (int pins : List.of(0, 7)) {
                    jdbc.update("DELETE FROM arte_ai_conversation_metadata");
                    var values = new ArrayList<Object[]>();
                    for (int i = 0; i < size; i++) {
                        String id = String.format("perf-%05d", i);
                        values.add(new Object[]{CanonicalJson.key(id), owner, id, "测试会话", i < pins, 1_000_000L + i});
                    }
                    jdbc.batchUpdate("INSERT INTO arte_ai_conversation_metadata(id_key,owner_key,conversation_id,title,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_text,user_message_count,last_message_micros,sort_micros,created_micros,statistics_ready,active) VALUES (?,?,?,?,0,?,false,false,NULL,0,NULL,?,1000000,true,true)", values);
                    var plan = jdbc.queryForObject("EXPLAIN SELECT conversation_id FROM arte_ai_conversation_metadata WHERE owner_key=? AND pinned=false AND active=true AND sort_micros<1005000 ORDER BY sort_micros DESC,conversation_id DESC LIMIT 21", String.class, owner);
                    assertTrue(plan, plan.toLowerCase(Locale.ROOT).contains("idx_ai_conversation_list"));
                    queries.clear();
                    var first = store.conversationList(OWNER, new ConversationList.Query(false, 20, null, true)).block();
                    assertEquals(Long.valueOf(size - pins), first.total());
                    assertEquals(20, first.records().size());
                    assertEquals(2, queries.size());
                    assertTrue(queries.stream().allMatch(q -> q.contains("arte_ai_conversation_metadata")));
                    queries.clear();
                    store.conversationList(OWNER, new ConversationList.Query(false, 20, first.nextCursor(), false)).block();
                    assertEquals(1, queries.size());
                    var window = store.conversationWindow(OWNER, new ConversationList.WindowQuery(100, null)).block();
                    assertEquals(pins, window.pinned().records().size());
                    assertEquals(100, window.pinned().records().size() + window.normal().records().size());
                    for (int warm = 0; warm < 10; warm++)
                        store.conversationList(OWNER, new ConversationList.Query(false, 20, first.nextCursor(), false)).block();
                    var elapsed = new long[30];
                    for (int i = 0; i < elapsed.length; i++) {
                        long start = System.nanoTime();
                        store.conversationList(OWNER, new ConversationList.Query(false, 20, first.nextCursor(), false)).block();
                        elapsed[i] = System.nanoTime() - start;
                    }
                    Arrays.sort(elapsed);
                    int bytes = JsonMapper.builder().build().writeValueAsString(first).getBytes(StandardCharsets.UTF_8).length;
                    System.out.printf(Locale.ROOT, "ARTE_LIST_BASELINE rows=%d pins=%d p50Ms=%.3f p95Ms=%.3f pageBytes=%d continuationSql=1%n",
                            size, pins, elapsed[14] / 1_000_000d, elapsed[28] / 1_000_000d, bytes);
                }
        } finally {
            scheduler.dispose();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T wrap(T target, Class<T> type, List<String> queries) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                var result = method.invoke(target, args);
                if (method.getName().equals("prepareStatement")) {
                    String sql = (String) args[0];
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (p, m, a) -> {
                                if (m.getName().equals("execute") || m.getName().equals("executeQuery"))
                                    queries.add(sql);
                                try {
                                    return m.invoke(result, a);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
