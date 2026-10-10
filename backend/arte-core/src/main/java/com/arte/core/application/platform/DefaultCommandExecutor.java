package com.arte.core.application.platform;

import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.spi.platform.CommandReceiptStore;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 同事务执行、回执防重及规范化摘要。所有治理锁持续到外层事务结束。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultCommandExecutor {

    public record Result<T>(T value, long version) {
    }

    private final PublicTransactionRunner transactions;
    private final CurrentPrincipalResolver principals;
    private final CommandReceiptStore receipts;
    private final PlatformIdGenerator ids;
    private final Clock clock;

    public DefaultCommandExecutor(PublicTransactionRunner transactions, CurrentPrincipalResolver principals,
                                  CommandReceiptStore receipts, PlatformIdGenerator ids, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions);
        this.principals = Objects.requireNonNull(principals);
        this.receipts = Objects.requireNonNull(receipts);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在同一事务中执行公共写命令，并通过命令回执保证幂等。
     * <p>
     * 同一主体、作用域、命令类型和操作键下，请求摘要一致时重新校验当前可见权限后返回原回执，不再执行领域写入；
     * 摘要不一致时拒绝请求。首次执行的领域写入（store）与回执（receipt）保存必须同事务提交，回执保存冲突时回滚本次写入。
     * <p>
     * 调用方负责提供服务端确定的作用域、命令类型和结果类型；领域回调须在持锁后完成
     * 当前写权限及预期版本校验（使用事务内当前读取，不能使用普通查询的旧快照），相关事件、审计与领域写入保持在当前线程和事务中。
     *
     * @param actor           已验证身份的操作者
     * @param meta            命令元信息，包含幂等操作键和追踪标识
     * @param scope           服务端确定的命令作用域
     * @param type            服务端确定的命令类型
     * @param schema          服务端认可的结果结构版本标识
     * @param resultType      服务端认可的结果视图类型，用于校验首次结果和已保存回执；Void.class 只接受 null，其他类型须有匹配的非空结果
     * @param fields          按确定顺序规范化的业务字段，包含预期版本，不包含追踪标识等非业务数据
     * @param lock            通过 WorkspaceGovernanceLocks 先锁主体、再按事务共享顺序取得治理锁的回调，锁须持续到外层事务结束
     * @param authorizeReplay 按当前权限校验原结果是否可见的回调；Void 命令接收 null，须按命令目标重检当前权限
     * @param work            执行领域校验和写入的回调，接收操作 ID，返回结果及结果版本
     * @param <T>             命令结果视图类型
     * @return 首次执行或幂等重放的回执；重放保留原操作 ID、结果版本和执行时间
     * @throws PlatformException 身份不可用、幂等冲突、已保存回执不可识别或回执写入冲突时抛出
     */
    public <T> CommandReceipt<T> execute(TrustedActor actor, CommandMeta meta, CommandScope scope,
                                         String type, String schema, Class<T> resultType, List<String> fields, Runnable lock,
                                         Consumer<T> authorizeReplay, Function<String, Result<T>> work) {
        Objects.requireNonNull(meta);
        Objects.requireNonNull(resultType);
        // 在进入事务前检查操作者当前是否有效。
        principals.requireActive(actor);
        // 完整命令身份定位回执，业务字段摘要用于识别同键不同请求。
        CommandIdentity identity = new CommandIdentity(actor.principal().subjectId(), scope, type, meta.operationKey());
        String digest = digest(fields);
        // 加入现有事务或开启新事务，领域写入与回执保存一起提交。
        return transactions.required(() -> {
            transactions.requireActive();
            // 获取治理锁；等待锁后再次检查身份，避免使用过期状态。
            lock.run();
            principals.requireActiveCurrent(actor);
            // 回执也用当前读取，避免外层旧快照看不到已提交的胜出操作。
            Optional<StoredCommandReceipt> saved = receipts.findCurrent(identity);
            if (saved.isPresent()) {
                StoredCommandReceipt stored = saved.get();
                // 同一操作键只能对应同一命令身份和请求内容。
                if (!stored.identity().equals(identity) || !stored.requestDigest().equals(digest)) {
                    throw new PlatformException(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, meta.traceId());
                }
                var receipt = stored.receipt();
                // 仅允许重放结构、类型和状态均可识别的成功回执。
                if (!stored.resultSchema().equals(schema) || !matchesResultType(resultType, receipt.value())
                        || receipt.status() != CommandStatusEnum.APPLIED) {
                    throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, meta.traceId());
                }
                T value = resultType.cast(receipt.value());
                // 重放仍需满足当前可见权限，不沿用首次执行时的授权。
                authorizeReplay.accept(value);
                return new CommandReceipt<>(receipt.operationId(), receipt.status(), CommandDeliveryEnum.REPLAYED,
                        value, receipt.resultVersion(), receipt.acceptedAt(), receipt.completedAt());
            }
            var acceptedAt = clock.instant();
            // 操作 ID 供领域写入、事件和审计关联使用。
            String operationId = ids.nextId();
            // 持锁执行领域回调，由回调完成写权限与版本校验。
            Result<T> result = work.apply(operationId);
            // 首次结果与重放使用同一类型规则，避免保存以后无法重放的回执。
            if (!matchesResultType(resultType, result.value())) {
                throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, meta.traceId());
            }
            var receipt = new CommandReceipt<>(operationId, CommandStatusEnum.APPLIED, CommandDeliveryEnum.APPLIED,
                    result.value(), result.version(), acceptedAt, clock.instant());
            // 原子保存回执，防止并发请求重复提交同一命令。
            if (!receipts.insertIfAbsent(new StoredCommandReceipt(identity, digest, schema, receipt, 0))) {
                // 整体回滚本次领域写入；调用者按原键重试读取胜出回执。
                throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, meta.traceId());
            }
            return receipt;
        });
    }

    private static boolean matchesResultType(Class<?> resultType, Object value) {
        // Class.isInstance(null) 恒为 false；Void 命令明确以 null 表示无结果。
        return resultType == Void.class ? value == null : resultType.isInstance(value);
    }

    /**
     * 长度前缀编码区分 null、空串及分隔符；集合字段由命令按确定顺序展开。
     */
    public static String digest(List<String> fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(fields.size());
                for (String field : fields) {
                    if (field == null) output.writeInt(-1);
                    else {
                        byte[] value = field.getBytes(StandardCharsets.UTF_8);
                        output.writeInt(value.length);
                        output.write(value);
                    }
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot encode command digest", e);
        }
    }
}
