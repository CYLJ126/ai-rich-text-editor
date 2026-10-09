package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.ClosureOperation;
import com.arte.core.pojo.workspace.ClosureWorkItem;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 耐久关闭协调存储，即工作空间关闭流程的持久化协调接口
 * <p>
 * 用于保存关闭操作、各领域工作项及处理进度，让流程能够跨进程重启恢复，并由多个后台实例协作推进。
 * 操作、工作项唯一，claim 在一个数据库事务内校验版本和租约、递增 fencing，重复回调不重复清理。
 * <p>
 * 关闭空间通常需要处理会话、模型配置、文章等资源，也可能需要等待正在执行的模型调用和费用核对，所以不能只修改一个空间状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ClosureWorkStore {

    /**
     * 在指定空间范围内查询关闭操作，不存在时返回空。
     */
    Optional<ClosureOperation> findOperation(Scope scope, String operationId);

    /**
     * 新增关闭操作，唯一条件冲突时返回 false。
     */
    boolean insertOperation(ClosureOperation operation);

    /**
     * 按预期版本更新关闭操作，版本条件不满足时返回 false。
     */
    boolean replaceOperation(ClosureOperation operation, long expectedVersion);

    /**
     * 分页查询尚未完成的关闭操作，供可靠扫描推进。
     */
    PageResult<ClosureOperation> listUnfinished(PageQuery query);

    /**
     * 新增关闭工作项，唯一条件冲突时返回 false。
     */
    boolean insertWorkIfAbsent(ClosureWorkItem item);

    /**
     * 分页查询指定空间关闭操作的工作项。
     */
    PageResult<ClosureWorkItem> listWork(Scope scope, String operationId, PageQuery query);

    /**
     * 原子校验版本和租约后领取工作项并递增 fencing，未能领取时返回空。
     */
    Optional<ClosureWorkItem> claim(Scope scope, String operationId, String domain, String workKey,
                                    long expectedVersion, String leaseOwner, Instant now, Duration leaseDuration);

    /**
     * 按预期版本和 fencing 更新工作项，条件不满足时返回 false。
     */
    boolean replaceWork(ClosureWorkItem item, long expectedVersion, long expectedFencing);
}
