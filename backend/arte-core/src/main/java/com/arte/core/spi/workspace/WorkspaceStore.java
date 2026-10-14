package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import com.arte.core.pojo.workspace.WorkspaceCandidatePage;

import java.util.Optional;

/**
 * 按完整范围存取空间，跨租户 ID 不可见；负责人不重复保存。候选查询不承担公共授权算法，服务须最终筛选及计算可见 total。
 * <p>
 * lock 返回取得锁后的最新事实（包括原事务自己的写入），不能使用旧快照、缓存或只读副本。
 * 锁持续到外层事务结束；不得通过另开事务取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceStore {

    /**
     * 按完整租户与空间范围查询空间，不存在或范围不匹配时返回空。
     */
    Optional<Workspace> find(Scope scope);

    /**
     * 在当前事务内按完整范围锁定并读取空间治理记录，须先持有对应排他租户父锁。
     */
    Optional<Workspace> lock(Scope scope);

    /**
     * 新增空间，唯一条件冲突时返回 false。
     */
    boolean insert(Workspace workspace);

    /**
     * 按预期版本更新空间，版本条件不满足或更新时间倒退时返回 false。
     * 创建时间与完整范围必须保留，不允许更新扫描稳定键；更新时间允许相等，不承担并发版本职责。
     */
    boolean replace(Workspace workspace, long expectedVersion);

    /**
     * 按 WorkspaceCandidateKey 顺序扫描：createdAt 降序、tenantId 和 workspaceId 升序，limit 为 1..100。
     * after 为服务端传递的完整边界，null 表示首批；严格向后查询，不保存 Redis token，不使用 offset。
     * 下一批不能按 updatedAt 等可变字段重新定位，也不能依赖边界记录仍存在；对外游标绑定由导航适配负责。
     * 示例后继条件：created_at &lt; lastCreatedAt，或时间相等且完整范围字典序大于 lastScope。
     * 不暴露候选总数；持续存在的候选不能因更新、改名或先前记录删除而漏扫。
     * 本接口不是成员目录快照：扫描期间新增／撤销关系仍逐项按当前资格过滤，新加入且位于边界前的候选可在下次查询出现。
     */
    WorkspaceCandidatePage scanMembershipCandidates(String subjectId, WorkspaceCandidateKey after, int limit);
}
