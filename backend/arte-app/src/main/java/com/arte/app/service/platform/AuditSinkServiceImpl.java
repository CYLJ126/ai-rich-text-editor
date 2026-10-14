package com.arte.app.service.platform;

import com.arte.app.platform.persistence.mapper.PlatformAuditMapper;
import com.arte.app.pojo.platform.PlatformAuditSinkDto;
import com.arte.core.pojo.platform.AuditEntry;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.utils.platform.PlatformTimePrecision;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 平台操作审计服务实现
 * <p>
 * 由组件扫描按开关装配，Mapper 固定绑定物理 app 库。
 * 复用当前业务事务，不另开事务，不切换线程，不访问远端日志服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 **/
@Service("auditSinkService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class AuditSinkServiceImpl extends ServiceImpl<PlatformAuditMapper, PlatformAuditSinkDto> implements AuditSink {

    private final PublicTransactionRunner tx;

    public AuditSinkServiceImpl(
            @Qualifier("platformAuditMapper") PlatformAuditMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx) {
        this.baseMapper = Objects.requireNonNull(mapper, "mapper");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void append(AuditEntry auditEntry) {
        tx.requireActive();
        try {
            PlatformTimePrecision.requireMicros(auditEntry.occurredAt());
            if (!save(convert(auditEntry))) {
                throw new IllegalStateException("Mandatory audit entry was not persisted");
            }
        } catch (RuntimeException | Error failure) {
            // 必要审计失败后，即使调用方捕获异常，也不能提交缺少审计的业务事务。
            tx.markRollbackOnly();
            throw failure;
        }
    }

    private PlatformAuditSinkDto convert(AuditEntry auditEntry) {
        Objects.requireNonNull(auditEntry, "auditEntry");
        var value = new PlatformAuditSinkDto();
        value.setId(auditEntry.id());
        value.setActorSubjectId(auditEntry.actorSubjectId());
        value.setTenantId(auditEntry.scope().tenantId());
        value.setWorkspaceId(auditEntry.scope().workspaceId());
        value.setAction(auditEntry.action());
        value.setOperationId(auditEntry.operationId());
        value.setReason(auditEntry.reason());
        value.setOccurredSec(auditEntry.occurredAt().getEpochSecond());
        value.setOccurredNano(auditEntry.occurredAt().getNano());
        return value;
    }
}
