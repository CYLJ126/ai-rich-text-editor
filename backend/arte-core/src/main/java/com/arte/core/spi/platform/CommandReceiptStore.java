package com.arte.core.spi.platform;

import com.arte.core.pojo.platform.CommandIdentity;
import com.arte.core.pojo.platform.StoredCommandReceipt;

import java.util.Optional;

/**
 * 同事务回执存储
 * <p>
 * 完整 CommandIdentity 唯一，相同键不同摘要拒绝；并发胜出回执必须可查询，原始身份须保留以核对摘要碰撞。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface CommandReceiptStore {

    /**
     * 按完整幂等命令标识查询已保存的回执，不存在时返回空。
     */
    Optional<StoredCommandReceipt> find(CommandIdentity identity);

    /**
     * 在当前治理事务中按完整命令身份读取最新已提交回执，包含原事务自己的写入。
     * 不读取旧快照、缓存或只读副本，不另开事务；普通查询不能作为默认回退。
     * 即使此前建立过读快照，也须能看到取得门控前已提交的胜出回执。
     */
    Optional<StoredCommandReceipt> findCurrent(CommandIdentity identity);

    /**
     * 按操作 ID 查询已保存的回执，不存在时返回空。
     */
    Optional<StoredCommandReceipt> findByOperationId(String operationId);

    /**
     * 新增幂等命令回执，唯一条件冲突时返回 false。
     */
    boolean insertIfAbsent(StoredCommandReceipt receipt);

    /**
     * 按预期版本更新回执，版本条件不满足时返回 false。
     */
    boolean replace(StoredCommandReceipt receipt, long expectedVersion);
}
