package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.persistence.mybatis.mapper.OutboxMapper;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.core.NestedExceptionUtils;

import java.sql.SQLException;
import java.util.Collection;
import java.util.function.LongSupplier;

/**
 * 独立新 AI 工厂的 SQL 诊断，只输出参数化 SQL，不输出绑定参数、快照或结果正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 22:28 ✾
 */
@Slf4j
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public final class ExecutionSqlDiagnostics implements Interceptor {

    private static final String SYSTEM_MAPPER_PREFIX = SystemMapper.class.getName() + ".";
    private static final String OUTBOX_PENDING_MESSAGES_STATEMENT = OutboxMapper.class.getName() + ".pendingMessages";
    private final NewAiSearchProperties.Diagnostics background;
    private final LongSupplier nanoTime;

    public ExecutionSqlDiagnostics() {
        this(null, System::nanoTime);
    }

    public ExecutionSqlDiagnostics(NewAiSearchProperties.Diagnostics background) {
        this(background, System::nanoTime);
    }

    ExecutionSqlDiagnostics(NewAiSearchProperties.Diagnostics background, LongSupplier nanoTime) {
        this.background = background;
        this.nanoTime = nanoTime;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var statement = (MappedStatement) invocation.getArgs()[0];
        if (statement.getId().startsWith(SYSTEM_MAPPER_PREFIX) || statement.getId().equals(OUTBOX_PENDING_MESSAGES_STATEMENT)) {
            return invocation.proceed();
        }
        var started = nanoTime.getAsLong();
        boolean verbose = background == null || background.sqlEnabled();
        String sql = null;
        try {
            if (verbose && log.isDebugEnabled()) {
                sql = sql(statement, invocation.getArgs()[1]);
                log.debug("New AI SQL started, statement={}, command={}, sql={}", statement.getId(), statement.getSqlCommandType(), sql);
            }
            var result = invocation.proceed();
            long elapsed = nanoTime.getAsLong() - started;
            if (verbose) log.debug("New AI SQL completed, statement={}, elapsedMs={}, rows={}", statement.getId(),
                    elapsed / 1_000_000, result instanceof Collection<?> rows ? rows.size() : result instanceof Number count ? count : null);
            if (background != null && elapsed >= background.slowSqlThreshold().toNanos()) {
                if (sql == null) sql = sql(statement, invocation.getArgs()[1]);
                log.warn("New AI background SQL slow, statement={}, command={}, elapsedMs={}, sql={}",
                        statement.getId(), statement.getSqlCommandType(), elapsed / 1_000_000, sql);
            }
            return result;
        } catch (Throwable failure) {
            if (sql == null) sql = sql(statement, invocation.getArgs()[1]);
            var root = NestedExceptionUtils.getMostSpecificCause(failure);
            var jdbc = root instanceof SQLException exception ? exception : null;
            log.error("New AI SQL failed, statement={}, command={}, elapsedMs={}, sqlState={}, vendorCode={}, rootType={}, sql={}",
                    statement.getId(), statement.getSqlCommandType(), (nanoTime.getAsLong() - started) / 1_000_000,
                    jdbc == null ? null : jdbc.getSQLState(), jdbc == null ? null : jdbc.getErrorCode(), root.getClass().getName(), sql);
            throw failure;
        }
    }

    private static String sql(MappedStatement statement, Object parameters) {
        try {
            return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException unavailable) {
            return "<unavailable>";
        }
    }
}
