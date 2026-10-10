package com.arte.ainew.persistence.mybatis;

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

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var statement = (MappedStatement) invocation.getArgs()[0];
        var started = System.nanoTime();
        String sql = null;
        try {
            sql = statement.getBoundSql(invocation.getArgs()[1]).getSql().replaceAll("\\s+", " ").trim();
            log.debug("New AI SQL started, statement={}, command={}, sql={}", statement.getId(), statement.getSqlCommandType(), sql);
            var result = invocation.proceed();
            log.debug("New AI SQL completed, statement={}, elapsedMs={}, rows={}", statement.getId(),
                    (System.nanoTime() - started) / 1_000_000, result instanceof Collection<?> rows ? rows.size() : result instanceof Number count ? count : null);
            return result;
        } catch (Throwable failure) {
            var root = NestedExceptionUtils.getMostSpecificCause(failure);
            var jdbc = root instanceof SQLException exception ? exception : null;
            log.error("New AI SQL failed, statement={}, command={}, elapsedMs={}, sqlState={}, vendorCode={}, rootType={}, sql={}",
                    statement.getId(), statement.getSqlCommandType(), (System.nanoTime() - started) / 1_000_000,
                    jdbc == null ? null : jdbc.getSQLState(), jdbc == null ? null : jdbc.getErrorCode(), root.getClass().getName(), sql);
            throw failure;
        }
    }
}
