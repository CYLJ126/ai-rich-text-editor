package com.arte.app.diagnostics;

import com.alibaba.druid.filter.FilterAdapter;
import com.alibaba.druid.filter.FilterChain;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.proxy.jdbc.ConnectionProxy;
import com.alibaba.druid.proxy.jdbc.PreparedStatementProxy;
import com.alibaba.druid.proxy.jdbc.ResultSetProxy;
import com.alibaba.druid.proxy.jdbc.StatementProxy;
import com.arte.core.diagnostics.RequestObservation;

import java.sql.SQLException;

/**
 * 在实际 JDBC 执行点记录，包含 MyBatis、身份的直接 JDBC 与事务内查询；不输出 SQL 参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
public final class NewAiJdbcObservationFilter extends FilterAdapter {

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute() throws SQLException;
    }

    private <T> T sql(StatementProxy statement, String sql, SqlWork<T> work) throws SQLException {
        var observation = RequestObservation.current();
        if (observation == null || !observation.sqlStarted()) return work.execute();
        long start = System.nanoTime();
        boolean failed = true;
        try {
            T value = work.execute();
            failed = false;
            return value;
        } finally {
            String normalized = sql == null ? "" : sql.toUpperCase(java.util.Locale.ROOT);
            observation.sql(System.nanoTime() - start, normalized.contains("FOR UPDATE") || normalized.contains("FOR SHARE"), failed,
                    mysqlId(statement.getConnectionProxy().getRawObject()));
        }
    }

    private static final ClassValue<java.util.Optional<java.lang.reflect.Method>> MYSQL_ID = new ClassValue<>() {
        @Override
        protected java.util.Optional<java.lang.reflect.Method> computeValue(Class<?> type) {
            if (!type.getName().startsWith("com.mysql.")) return java.util.Optional.empty();
            try {
                return java.util.Optional.of(type.getMethod("getId"));
            } catch (NoSuchMethodException ignored) {
                return java.util.Optional.empty();
            }
        }
    };

    static Long mysqlId(Object connection) {
        try {
            if (connection instanceof com.alibaba.druid.pool.DruidStatementConnection wrapped)
                connection = wrapped.getConnection();
            if (connection instanceof ConnectionProxy proxy) connection = proxy.getRawObject();
            // 驱动 ID 是 CONNECTION_ID()，避免为了诊断额外发一次 SQL。
            var method = MYSQL_ID.get(connection.getClass());
            if (method.isPresent()) return ((Number) method.get().invoke(connection)).longValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* 诊断不能改变数据库操作结果 */ }
        return null;
    }

    @Override
    public DruidPooledConnection dataSource_getConnection(FilterChain chain, DruidDataSource source, long maxWait) throws SQLException {
        var observation = RequestObservation.current();
        if (observation == null) return chain.dataSource_connect(source, maxWait);
        long start = System.nanoTime();
        boolean failed = true;
        try {
            var value = chain.dataSource_connect(source, maxWait);
            failed = false;
            return value;
        } finally {
            observation.connection(System.nanoTime() - start, failed);
        }
    }

    @Override
    public boolean preparedStatement_execute(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
        return sql(statement, statement.getSql(), () -> chain.preparedStatement_execute(statement));
    }

    @Override
    public ResultSetProxy preparedStatement_executeQuery(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
        return sql(statement, statement.getSql(), () -> chain.preparedStatement_executeQuery(statement));
    }

    @Override
    public int preparedStatement_executeUpdate(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
        return sql(statement, statement.getSql(), () -> chain.preparedStatement_executeUpdate(statement));
    }

    @Override
    public int[] statement_executeBatch(FilterChain chain, StatementProxy statement) throws SQLException {
        return sql(statement, statement.getBatchSql(), () -> chain.statement_executeBatch(statement));
    }

    @Override
    public ResultSetProxy statement_executeQuery(FilterChain chain, StatementProxy statement, String query) throws SQLException {
        return sql(statement, query, () -> chain.statement_executeQuery(statement, query));
    }

    @Override
    public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String query) throws SQLException {
        return sql(statement, query, () -> chain.statement_executeUpdate(statement, query));
    }

    @Override
    public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String query, int keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_executeUpdate(statement, query, keys));
    }

    @Override
    public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String query, int[] keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_executeUpdate(statement, query, keys));
    }

    @Override
    public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String query, String[] keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_executeUpdate(statement, query, keys));
    }

    @Override
    public boolean statement_execute(FilterChain chain, StatementProxy statement, String query) throws SQLException {
        return sql(statement, query, () -> chain.statement_execute(statement, query));
    }

    @Override
    public boolean statement_execute(FilterChain chain, StatementProxy statement, String query, int keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_execute(statement, query, keys));
    }

    @Override
    public boolean statement_execute(FilterChain chain, StatementProxy statement, String query, int[] keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_execute(statement, query, keys));
    }

    @Override
    public boolean statement_execute(FilterChain chain, StatementProxy statement, String query, String[] keys) throws SQLException {
        return sql(statement, query, () -> chain.statement_execute(statement, query, keys));
    }
}
