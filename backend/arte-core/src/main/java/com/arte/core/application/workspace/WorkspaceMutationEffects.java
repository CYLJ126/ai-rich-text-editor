package com.arte.core.application.workspace;

import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.pojo.platform.AuditEntry;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.PublicEvent;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.platform.PublicOutboxStore;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 与领域写入、回执同事务记录最小事件及审计；适配器提交后再投递。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class WorkspaceMutationEffects {

    private final PublicOutboxStore outbox;
    private final AuditSink audit;
    private final PlatformIdGenerator ids;
    private final Clock clock;

    public WorkspaceMutationEffects(PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.audit = Objects.requireNonNull(audit);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    public void append(TrustedActor actor, CommandScope scope, String action, String operationId, long version,
                       Map<String, String> attributes) {
        Map<String, String> metadata = new HashMap<>(attributes);
        metadata.put("operationId", operationId);
        var observedAt = PlatformTimes.businessNow(clock);
        outbox.append(new PublicEvent(ids.nextId(), scope, action, 1, version, observedAt, metadata));
        audit.append(new AuditEntry(ids.nextId(), actor.principal().subjectId(), scope, action, operationId, "", observedAt));
    }
}
