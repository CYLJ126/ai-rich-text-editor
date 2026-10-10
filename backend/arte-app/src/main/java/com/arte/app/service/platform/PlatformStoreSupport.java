package com.arte.app.service.platform;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.utils.platform.PlatformChecks;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 平台 ServiceImpl 的事务协作基础
 * <p>
 * 仅共享同事务门控和写入失败处理，不实现授权算法。
 * 所有 SQL 由 Mapper 执行，治理业务只通过 core SPI 调用具体服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
abstract class PlatformStoreSupport<M extends BaseMapper<D>, D> extends ServiceImpl<M, D> {

    protected final PublicTransactionRunner tx;

    protected PlatformStoreSupport(M mapper, PublicTransactionRunner tx) {
        this.baseMapper = Objects.requireNonNull(mapper, "mapper");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    protected <R> Optional<R> one(Supplier<D> query, Function<D, R> convert) {
        return Optional.ofNullable(query.get()).map(convert);
    }

    protected boolean insertRecord(IntSupplier work) {
        tx.requireActive();
        try {
            return work.getAsInt() == 1;
        } catch (DuplicateKeyException conflict) {
            return false;
        } catch (RuntimeException | Error failure) {
            tx.markRollbackOnly();
            throw failure;
        }
    }

    protected int write(IntSupplier work) {
        tx.requireActive();
        try {
            return work.getAsInt();
        } catch (RuntimeException | Error failure) {
            tx.markRollbackOnly();
            throw failure;
        }
    }

    protected boolean updateRecord(IntSupplier work) {
        return write(work) == 1;
    }

    private static final Object GATE_STATE = new Object();

    private static final class Gates {
        final Set<String> subjects = new HashSet<>();
        final Set<String> tenants = new HashSet<>();
        final Set<Scope> scopes = new HashSet<>();
    }

    private Gates gates() {
        tx.requireActive();
        return tx.resource(GATE_STATE, Gates::new);
    }

    protected void subjectHeld(String subject) {
        gates().subjects.add(subject);
    }

    protected void requireSubject(String subject) {
        if (!gates().subjects.contains(subject))
            throw new IllegalStateException("Subject gate required");
    }

    protected void requireAnySubject() {
        if (gates().subjects.isEmpty())
            throw new IllegalStateException("Subject gate required before tenant");
    }

    protected void tenantHeld(String tenant) {
        gates().tenants.add(tenant);
    }

    protected void requireTenant(String tenant) {
        if (!gates().tenants.contains(tenant))
            throw new IllegalStateException("Parent tenant gate required");
    }

    protected void scopeHeld(Scope scope) {
        gates().scopes.add(scope);
    }

    protected void requireScope(Scope scope) {
        requireTenant(scope.tenantId());
        if (!gates().scopes.contains(scope))
            throw new IllegalStateException("Workspace gate required");
    }

    protected static String subjectDigest(String subject) {
        PlatformChecks.id(subject, "subjectId");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    protected static void next(long actual, long expected) {
        if (expected < 0 || actual != Math.addExact(expected, 1))
            throw new IllegalArgumentException("Version must advance by one");
    }
}
