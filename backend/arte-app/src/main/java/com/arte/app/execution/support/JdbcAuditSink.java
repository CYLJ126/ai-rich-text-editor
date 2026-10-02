package com.arte.app.execution.support;

import com.arte.base.model.observability.AuditReceipt;
import com.arte.base.model.observability.AuditRecord;
import com.arte.base.spi.observability.AuditSink;
import com.arte.base.validation.ContractChecks;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;

/**
 * 同步耐久追加；独立事务提交成功后才返回，重试相同事件不会覆盖原事实。
 */
public final class JdbcAuditSink implements AuditSink {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final Clock clock;

    public JdbcAuditSink(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = ContractChecks.required(jdbc, "jdbc");
        this.clock = ContractChecks.required(clock, "clock");
        writes = writeTransaction(manager);
    }

    static TransactionTemplate writeTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(ContractChecks.required(manager, "transactionManager"));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    @Override
    public AuditReceipt append(AuditRecord record) {
        ContractChecks.required(record, "record");
        byte[] encoded = SupportEncoding.audit(record);
        String digest = SupportEncoding.digest(encoded);
        return writes.execute(status -> {
            var now = clock.instant();
            try {
                jdbc.update("INSERT INTO arte_execution_audit (event_id, scope_key, event_type, payload, content_digest, recorded_at) VALUES (?, ?, ?, ?, ?, ?)",
                        record.eventId(), SupportEncoding.scopeKey(record.scope()), record.eventType(), encoded, digest, Timestamp.from(now));
                return jdbc.queryForObject("SELECT recorded_at FROM arte_execution_audit WHERE event_id = ?", (rs, row) ->
                        new AuditReceipt(record.eventId(), digest, rs.getTimestamp("recorded_at").toInstant()), record.eventId());
            } catch (DuplicateKeyException duplicate) {
                return jdbc.queryForObject("SELECT payload, content_digest, recorded_at FROM arte_execution_audit WHERE event_id = ?", (rs, row) -> {
                    byte[] stored = rs.getBytes("payload");
                    if (!Arrays.equals(encoded, stored) || !digest.equals(rs.getString("content_digest"))) {
                        throw new IllegalStateException("audit event conflict or integrity failure");
                    }
                    return new AuditReceipt(record.eventId(), digest, rs.getTimestamp("recorded_at").toInstant());
                }, record.eventId());
            }
        });
    }
}
