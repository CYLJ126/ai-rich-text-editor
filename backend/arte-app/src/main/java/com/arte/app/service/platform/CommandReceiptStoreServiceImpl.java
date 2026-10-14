package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformCommandMapper;
import com.arte.app.pojo.platform.PlatformCommandDto;
import com.arte.core.pojo.platform.CommandIdentity;
import com.arte.core.pojo.platform.StoredCommandReceipt;
import com.arte.core.spi.platform.CommandReceiptStore;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.utils.platform.PlatformTimePrecision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 完整命令身份幂等回执服务
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("commandReceiptStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class CommandReceiptStoreServiceImpl extends PlatformStoreSupport<PlatformCommandMapper, PlatformCommandDto> implements CommandReceiptStore {

    private final PlatformJson json;

    public CommandReceiptStoreServiceImpl(
            @Qualifier("platformCommandMapper") PlatformCommandMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
            @Qualifier("platformJson") PlatformJson json) {
        super(mapper, tx);
        this.json = Objects.requireNonNull(json);
    }

    private static String digest(CommandIdentity identity) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (String v :
                    Arrays.asList(
                            identity.actorSubjectId(),
                            identity.scope().tenantId(),
                            identity.scope().workspaceId(),
                            identity.commandType(),
                            identity.operationKey())) {
                if (v == null) out.writeInt(-1);
                else {
                    var b = v.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(b.length);
                    out.write(b);
                }
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot encode command identity", failure);
        }
    }

    private StoredCommandReceipt decode(PlatformCommandDto row) {
        var value = json.receipt(row.getReceipt());
        if (!value.receipt().operationId().equals(row.getOperationId()) || value.version() != row.getVersion()
                || !digest(value.identity()).equals(row.getIdentityDigest())
                || !value.identity().actorSubjectId().equals(row.getActorId())
                || !Objects.equals(value.identity().scope().tenantId(), row.getTenantId())
                || !Objects.equals(value.identity().scope().workspaceId(), row.getWorkspaceId())
                || !value.identity().commandType().equals(row.getCommandType())
                || !value.identity().operationKey().equals(row.getOperationKey())
                || !value.requestDigest().equals(row.getRequestDigest()) || !value.resultSchema().equals(row.getResultSchema()))
            throw new IllegalStateException("Corrupt command envelope");
        return value;
    }

    private PlatformCommandDto encode(StoredCommandReceipt value) {
        json.validate(value);
        var row = new PlatformCommandDto();
        row.setOperationId(value.receipt().operationId());
        row.setIdentityDigest(digest(value.identity()));
        row.setActorId(value.identity().actorSubjectId());
        row.setTenantId(value.identity().scope().tenantId());
        row.setWorkspaceId(value.identity().scope().workspaceId());
        row.setCommandType(value.identity().commandType());
        row.setOperationKey(value.identity().operationKey());
        row.setRequestDigest(value.requestDigest());
        row.setResultSchema(value.resultSchema());
        row.setReceipt(json.write(value));
        row.setVersion(value.version());
        return row;
    }

    @Override
    public Optional<StoredCommandReceipt> find(CommandIdentity identity) {
        return find(identity, false);
    }

    @Override
    public Optional<StoredCommandReceipt> findCurrent(CommandIdentity identity) {
        tx.requireActive();
        return find(identity, true);
    }

    private Optional<StoredCommandReceipt> find(CommandIdentity identity, boolean current) {
        var found = one(() -> baseMapper.selectIdentity(digest(identity), current), this::decode);
        found.ifPresent(value -> {
            if (!value.identity().equals(identity))
                throw new IllegalStateException("Command identity digest collision");
        });
        return found;
    }

    @Override
    public Optional<StoredCommandReceipt> findByOperationId(String id) {
        return one(() -> baseMapper.selectById(id), this::decode);
    }

    @Override
    public boolean insertIfAbsent(StoredCommandReceipt value) {
        // 新回执必须已在领域生成边界规范化；读取和重放历史回执保留原始精度。
        PlatformTimePrecision.requireMicros(value.receipt().acceptedAt());
        if (value.receipt().completedAt() != null)
            PlatformTimePrecision.requireMicros(value.receipt().completedAt());
        boolean inserted = insertRecord(() -> baseMapper.insert(encode(value)));
        if (!inserted) {
            findCurrent(value.identity());
            var byId = findByOperationId(value.receipt().operationId());
            if (byId.isPresent() && !byId.get().identity().equals(value.identity()))
                throw new IllegalStateException("Operation ID conflict");
        }
        return inserted;
    }

    @Override
    public boolean replace(StoredCommandReceipt value, long expected) {
        next(value.version(), expected);
        json.validate(value);
        var current = findCurrent(value.identity());
        if (current.isEmpty() || current.get().version() != expected) return false;
        var old = current.get();
        if (!old.receipt().acceptedAt().equals(value.receipt().acceptedAt())
                || !old.receipt().operationId().equals(value.receipt().operationId())
                || !old.requestDigest().equals(value.requestDigest()) || !old.resultSchema().equals(value.resultSchema()))
            throw new IllegalArgumentException("Immutable command identity changed");
        return updateRecord(() -> baseMapper.replaceCas(encode(value), expected));
    }
}
