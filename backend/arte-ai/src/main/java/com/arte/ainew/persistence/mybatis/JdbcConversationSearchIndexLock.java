package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.search.ConversationSearchIndexLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.Callable;

/**
 * MySQL 连接级命名锁；同一别名对跨代际串行，不新增表、不持有业务事务。
 * 持锁期间占用一条连接，后台进度短事务需另一条连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class JdbcConversationSearchIndexLock implements ConversationSearchIndexLock {

    private final DataSource dataSource;
    private final String name;
    private final int timeout;

    public JdbcConversationSearchIndexLock(DataSource dataSource, NewAiSearchProperties p) {
        this.dataSource = dataSource;
        name = CanonicalJson.key("conversation-search-bootstrap", p.index().readAlias(), p.index().writeAlias());
        timeout = Math.max(1, (int) p.limits().requestTimeout().toSeconds());
    }

    @Override
    public <T> T locked(Callable<T> action) throws Exception {
        T result;
        try (var connection = dataSource.getConnection()) {
            if (scalar(connection, "SELECT GET_LOCK(?, 0)") != 1)
                throw new IllegalStateException("SEARCH_INDEX_MAINTENANCE_BUSY");
            try {
                result = action.call();
            } finally {
                // 池化连接必须释放命名锁；释放失败废弃连接，避免把锁带回连接池。
                try {
                    if (scalar(connection, "SELECT RELEASE_LOCK(?)") != 1)
                        throw new IllegalStateException("SEARCH_INDEX_LOCK_LOST");
                } catch (Exception releaseFailure) {
                    connection.abort(Runnable::run);
                    throw releaseFailure;
                }
            }
        }
        return result;
    }

    private long scalar(Connection c, String sql) throws Exception {
        try (var statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return -1;
                long value = result.getLong(1);
                return result.wasNull() ? -1 : value;
            }
        }
    }
}
