package com.arte.app.platform.identity;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 授权原始事实的短期复用容器，减少同一次同步授权检查中重复查询账户、身份映射和 RBAC 的 SQL。
 * 只保存读取事实，不缓存最终允许决定；每项授权规则仍由调用方检查。
 * 状态借助事务同步器关联实际事务，REQUIRES_NEW 不会取得被挂起事务的事实；
 * 授权回调退出即清空，业务写入及下一次提交复核必须重新读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 17:46 ✾
 */
final class RbacAuthorizationReads implements TransactionSynchronization {

    private final DataSource source;
    private Map<Fact, Object> facts;

    /**
     * 以读取器和查询键共同标识事实，隔离不同读取器、账户及读取模式。
     */
    private record Fact(Object reader, Object key) {
    }

    /**
     * 创建指定物理数据源的事务容器；授权批次开始时才分配事实集合。
     */
    private RbacAuthorizationReads(DataSource source) {
        this.source = source;
    }

    /**
     * 在当前可写事务中开启或加入一次授权读取批次；嵌套调用共享事实，最外层回调退出时清空。
     * 只包住授权检查，不得把业务写入、异步订阅或线程切换放入回调。
     *
     * @param source 当前事务绑定的物理数据源
     * @param work   同步执行的授权检查
     * @param <T>    授权检查的返回值类型
     * @return 授权检查的原始结果
     */
    static <T> T during(DataSource source, Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Authorization reads require a writable appDataSource transaction");
        var batch = current(source);
        if (batch == null) {
            batch = new RbacAuthorizationReads(source);
            TransactionSynchronizationManager.registerSynchronization(batch);
        }
        if (batch.facts != null) return work.get();
        batch.facts = new HashMap<>();
        try {
            return work.get();
        } finally {
            batch.facts.clear();
            batch.facts = null;
        }
    }

    /**
     * 优先复用本批已读取的事实，未命中时执行加载；不在授权批次内时直接读取，不保存结果。
     * 普通读与锁定当前读由调用方使用不同 key，不能以快照事实替代锁定读取。
     *
     * @param source 事实所属的物理数据源
     * @param reader 读取器标识，用于隔离不同读取器的事实
     * @param key    包含查询目标及读取模式的键
     * @param load   未命中时执行的实际读取操作
     * @param <T>    原始事实的类型
     * @return 已复用或本次加载的事实；null 和空 Optional 不保存
     */
    @SuppressWarnings("unchecked")
    static <T> T read(DataSource source, Object reader, Object key, Supplier<T> load) {
        var batch = current(source);
        if (batch == null || batch.facts == null) return load.get();
        var fact = new Fact(reader, key);
        if (batch.facts.containsKey(fact)) return (T) batch.facts.get(fact);
        T value = load.get();
        // 未找到的身份可能由本事务首次初始化；不保留缺失结果。
        if (value != null && !(value instanceof Optional<?> optional && optional.isEmpty()))
            batch.facts.put(fact, value);
        return value;
    }

    /**
     * 为已读取的事实登记另一个查询键，使主体键与外部账户键可共用同一身份行。
     * 仅在授权批次内保存，不执行查询；调用方须保证别名对应同一事实及读取模式。
     *
     * @param source 事实所属的物理数据源
     * @param reader 读取器标识
     * @param key    待登记的查询键或别名键
     * @param value  已读取且可供本批复用的原始事实
     */
    static void remember(DataSource source, Object reader, Object key, Object value) {
        var batch = current(source);
        if (batch != null && batch.facts != null) batch.facts.put(new Fact(reader, key), value);
    }

    /**
     * 从当前实际事务的同步器列表中查找指定数据源的容器，不创建容器或开启授权批次。
     *
     * @param source 待匹配的物理数据源，按实例匹配
     * @return 当前事务已有的容器；缺少事务、数据源绑定、同步机制或匹配容器时返回 null
     */
    private static RbacAuthorizationReads current(DataSource source) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)
                || !TransactionSynchronizationManager.isSynchronizationActive()) return null;
        return TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(value -> value instanceof RbacAuthorizationReads batch && batch.source == source)
                .map(RbacAuthorizationReads.class::cast).findFirst().orElse(null);
    }
}
