package com.arte.core.interceptor;

import com.arte.core.annotations.MybatisParams;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 JDBC 绑定与 MyBatis 执行器验证 SQL 改写，不以 SQL 字符串拼装代替执行。
 */
public class MybatisInterceptorTest {
    @MybatisParams("demo")
    public interface DemoMapper {
        @Insert("INSERT INTO demo(id) VALUES(#{id})")
        int insert(int id);

        @Insert("INSERT INTO demo(id,create_by) VALUES(#{id},#{actor})")
        int explicitAudit(@Param("id") int id, @Param("actor") String actor);

        @Insert({"<script>INSERT INTO demo(id) VALUES <foreach collection='ids' item='id' separator=','>(#{id})</foreach></script>"})
        int many(@Param("ids") List<Integer> ids);

        @Insert("INSERT INTO demo(id) SELECT 99")
        int insertSelect();

        @Insert("INSERT INTO demo(id) VALUES(1) ON DUPLICATE KEY UPDATE id=id")
        int upsert();

        @Select("SELECT id FROM demo WHERE id>=#{after} ORDER BY id LIMIT #{size}")
        List<Integer> limited(@Param("after") int after, @Param("size") int size);

        @Select("SELECT a.id FROM demo a JOIN demo b ON a.id=b.id WHERE a.id>=#{after} ORDER BY a.id LIMIT #{size}")
        List<Integer> joined(@Param("after") int after, @Param("size") int size);

        @Select("SELECT id FROM demo WHERE id=1 UNION ALL SELECT id FROM demo WHERE id=2")
        List<Integer> union();

        @Select("SELECT id FROM demo WHERE id IN (SELECT id FROM demo WHERE id>=#{after}) ORDER BY id LIMIT #{size}")
        List<Integer> nested(@Param("after") int after, @Param("size") int size);

        @Select("WITH a AS (SELECT * FROM demo WHERE id>=#{after}) SELECT id FROM a ORDER BY id LIMIT #{size}")
        List<Integer> cte(@Param("after") int after, @Param("size") int size);

        @MybatisParams("a")
        @Select("WITH a AS (SELECT * FROM demo) SELECT id FROM a ORDER BY id")
        List<Integer> cteAlias();

        @Select("SELECT CASE WHEN EXISTS(SELECT id FROM demo WHERE id=2) THEN 1 ELSE 0 END FROM demo WHERE id=1")
        int caseSubquery();

        @Select("SELECT id FROM demo WHERE id<=ANY(SELECT id FROM demo WHERE id=2)")
        List<Integer> anySubquery();

        @Delete("WITH c AS (SELECT id FROM demo WHERE id=#{id}) DELETE FROM demo WHERE id IN (SELECT id FROM c)")
        int cteDelete(int id);

        @Update("UPDATE demo SET payload=10 WHERE id=1; UPDATE demo SET payload=20 WHERE id=2")
        int multiStatementUpdate();

        @Select("SELECT id FROM other_table")
        List<Integer> otherTable();

        @MybatisParams(value = "demo", queryFields = {"unsupported"})
        @Select("SELECT id FROM demo")
        List<Integer> unsupportedField();

        @Select("SELECT CURRENT_TIMESTAMP")
        java.sql.Timestamp clock();

        @Select("SELECT '?' FROM demo WHERE id=1 /* ? :ignored */")
        List<String> literal();

        @MybatisParams(ignore = true)
        @Select("SELECT id FROM demo ORDER BY id")
        List<Integer> ignored(Entity entity);

        @Update("UPDATE demo SET payload=#{payload} WHERE id=#{id} OR id=#{other}")
        int update(@Param("id") int id, @Param("other") int other, @Param("payload") int payload);

        @Delete("DELETE FROM demo WHERE id=#{id}")
        int delete(int id);
    }

    public interface EntityMapper {
        @Select("SELECT id FROM demo ORDER BY id")
        List<Integer> wrapped(@Param("entity") Entity entity);
    }

    @MybatisParams("demo")
    public static class Entity {
    }

    private JdbcDataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeEach
    public void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE demo(id INT PRIMARY KEY,payload INT,create_by VARCHAR(128),create_time TIMESTAMP,update_by VARCHAR(128),update_time TIMESTAMP)");
            sql.execute("INSERT INTO demo(id,payload,create_by) VALUES(1,0,'alice'),(2,0,'bob'),(3,0,'alice')");
            sql.execute("CREATE TABLE other_table(id INT)");
            sql.execute("INSERT INTO other_table VALUES(7)");
        }
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        var plugin = new MybatisPlusInterceptor();
        plugin.addInnerInterceptor(new MybatisQueryInterceptor());
        plugin.addInnerInterceptor(new MybatisInsertInterceptor());
        plugin.addInnerInterceptor(new MybatisUpdateInterceptor());
        plugin.addInnerInterceptor(new MybatisDeleteInterceptor());
        config.addInterceptor(plugin);
        config.addMapper(DemoMapper.class);
        config.addMapper(EntityMapper.class);
        factory = new SqlSessionFactoryBuilder().build(config);
        actor("alice");
    }

    @AfterEach
    public void cleanup() throws Exception {
        UserContext.clear();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("DROP ALL OBJECTS");
        }
    }

    private static void actor(String name) {
        UserContext.setUserOnlineInfo(new UserOnlineInfo().setUserName(name));
    }

    private Object scalar(String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var query = connection.createStatement(); var rows = query.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getObject(1);
        }
    }

    @Test
    public void limitJoinNestedUnionAndCteBindInSqlOrder() {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertEquals(List.of(1, 3), mapper.limited(1, 2));
            assertEquals(List.of(1, 3), mapper.joined(1, 2));
            assertEquals(List.of(1, 3), mapper.nested(1, 2));
            assertEquals(List.of(1), mapper.union());
            assertEquals(List.of(1, 3), mapper.cte(1, 2));
            assertEquals(List.of(1, 3), mapper.cteAlias());
            assertEquals(0, mapper.caseSubquery());
            assertEquals(List.of(), mapper.anySubquery());
        }
    }

    @Test
    public void unrelatedTableClockAndQuotedTokensDoNotCreatePhantomParameters() {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertEquals(List.of("?"), mapper.literal());
            UserContext.clear();
            assertEquals(List.of(7), mapper.otherTable());
            assertNotNull(mapper.clock());
        }
    }

    @Test
    public void methodIgnoreWinsAndWrappedEntityAnnotationIsRecognized() {
        try (var session = factory.openSession()) {
            assertEquals(List.of(1, 2, 3), session.getMapper(DemoMapper.class).ignored(new Entity()));
            assertEquals(List.of(1, 3), session.getMapper(EntityMapper.class).wrapped(new Entity()));
        }
    }

    @Test
    public void missingIdentityAndUnknownFieldFailClosed() {
        UserContext.clear();
        try (var session = factory.openSession()) {
            assertThrows(RuntimeException.class, () -> session.getMapper(DemoMapper.class).limited(1, 2));
            assertThrows(RuntimeException.class, () -> session.getMapper(DemoMapper.class).insert(4));
            assertThrows(RuntimeException.class, () -> session.getMapper(DemoMapper.class).delete(1));
            actor("alice");
            assertThrows(RuntimeException.class, () -> session.getMapper(DemoMapper.class).unsupportedField());
        }
    }

    @Test
    public void multiRowForeachAndQuotedActorUseBoundAuditValues() throws Exception {
        actor("O'Reilly");
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertEquals(2, mapper.many(List.of(4, 5)));
            assertEquals(1, mapper.explicitAudit(6, "client-forged"));
            session.commit();
        }
        assertEquals(3L, scalar("SELECT COUNT(*) FROM demo WHERE create_by='O''Reilly'"));
        assertEquals(3L, scalar("SELECT COUNT(*) FROM demo WHERE id>=4 AND create_time IS NOT NULL AND update_time IS NOT NULL"));
    }

    @Test
    public void batchBindsEachActorAndReusedStatementsKeepFreshAuditValues() throws Exception {
        for (ExecutorType executor : List.of(ExecutorType.BATCH, ExecutorType.REUSE)) {
            int first = executor == ExecutorType.BATCH ? 4 : 6;
            try (var session = factory.openSession(executor)) {
                actor("alice");
                session.getMapper(DemoMapper.class).insert(first);
                actor("bob");
                session.getMapper(DemoMapper.class).insert(first + 1);
                session.commit();
            }
            assertEquals("alice", scalar("SELECT create_by FROM demo WHERE id=" + first));
            assertEquals("bob", scalar("SELECT create_by FROM demo WHERE id=" + (first + 1)));
        }
        actor("alice");
        try (var session = factory.openSession()) {
            assertEquals(List.of(1, 3, 4, 6), session.getMapper(DemoMapper.class).limited(1, 9));
        }
    }

    @Test
    public void updateAndDeleteCannotChangeAnotherCreatorAndOrIsGrouped() throws Exception {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertEquals(1, mapper.update(1, 2, 42));
            assertEquals(0, mapper.delete(2));
            assertEquals(1, mapper.delete(3));
            session.commit();
        }
        assertEquals(42, scalar("SELECT payload FROM demo WHERE id=1"));
        assertEquals(0, scalar("SELECT payload FROM demo WHERE id=2"));
        assertEquals("alice", scalar("SELECT update_by FROM demo WHERE id=1"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM demo WHERE id=3"));
    }

    @Test
    public void scopeRestoresOnFailureAndSupportsNesting() {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertThrows(IllegalArgumentException.class, () -> {
                try (var outer = MybatisInterceptor.ignoreScope()) {
                    assertEquals(List.of(1, 2, 3), mapper.limited(1, 9));
                    try (var inner = MybatisInterceptor.ignoreScope()) {
                        assertEquals(3, mapper.limited(1, 9).size());
                    }
                    assertEquals(3, mapper.limited(1, 9).size());
                    throw new IllegalArgumentException("injected caller failure");
                }
            });
            assertEquals(List.of(1, 3), mapper.limited(1, 9));
        }
    }

    @Test
    public void selectAndUpsertFailInsteadOfExecutingWithoutAudit() throws Exception {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(DemoMapper.class);
            assertThrows(RuntimeException.class, mapper::insertSelect);
            assertThrows(RuntimeException.class, mapper::upsert);
            session.rollback();
        }
        assertEquals(3L, scalar("SELECT COUNT(*) FROM demo"));
    }

    @Test
    public void semicolonBatchRewritesEveryStatementWithoutLosingBindingOrder() {
        var mapped = factory.getConfiguration().getMappedStatement(DemoMapper.class.getName() + ".multiStatementUpdate");
        new MybatisUpdateInterceptor().beforeUpdate(null, mapped, null);
        var bound = mapped.getBoundSql(null);
        String[] statements = bound.getSql().split(";");
        assertEquals(2, statements.length);
        assertTrue(Arrays.stream(statements).allMatch(sql -> sql.contains("create_by = ?") && sql.contains("update_by = ?")));
        assertEquals(6, bound.getParameterMappings().size());
        assertFalse(bound.getSql().contains("alice"));
    }

    @Test
    public void cteDeleteGuardsBothTheSourceAndTheTargetTable() {
        var mapped = factory.getConfiguration().getMappedStatement(DemoMapper.class.getName() + ".cteDelete");
        new MybatisDeleteInterceptor().beforeUpdate(null, mapped, Map.of("id", 1));
        var bound = mapped.getBoundSql(Map.of("id", 1));
        assertEquals(2, bound.getSql().split("create_by = \\?", -1).length - 1);
        assertEquals(3, bound.getParameterMappings().size());
    }

    @Test
    public void dataModifyingCteIsRejectedExplicitlyWithoutExecutingSql() throws Exception {
        var config = factory.getConfiguration();
        for (String modification : List.of(
                "DELETE FROM demo WHERE id=1 RETURNING id",
                "UPDATE demo SET payload=42 WHERE id=1 RETURNING id",
                "INSERT INTO demo(id) VALUES(4) RETURNING id")) {
            var mapped = new MappedStatement.Builder(config, DemoMapper.class.getName() + ".limited",
                    new StaticSqlSource(config, "WITH c AS (" + modification + ") SELECT id FROM c"),
                    SqlCommandType.SELECT).build();
            var bound = mapped.getBoundSql(null);
            String originalSql = bound.getSql();
            var error = assertThrows(IllegalStateException.class, () -> new MybatisQueryInterceptor()
                    .beforeQuery(null, mapped, null, RowBounds.DEFAULT, null, bound));
            assertEquals("Unsupported data-modifying CTE", error.getMessage());
            assertEquals(originalSql, bound.getSql());
        }
        assertEquals(3L, scalar("SELECT COUNT(*) FROM demo"));
        assertEquals(0, scalar("SELECT payload FROM demo WHERE id=1"));
    }

    @Test
    public void parserFailureIsNotSwallowed() {
        var config = factory.getConfiguration();
        var mapped = new MappedStatement.Builder(config, DemoMapper.class.getName() + ".insert",
                new StaticSqlSource(config, "NOT VALID SQL"), SqlCommandType.INSERT).build();
        new MybatisInsertInterceptor().beforeUpdate(null, mapped, null);
        assertThrows(IllegalStateException.class, () -> mapped.getBoundSql(null));
    }
}
