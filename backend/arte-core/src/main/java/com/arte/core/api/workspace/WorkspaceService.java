package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;

/**
 * 空间公共 API
 * <p>
 * 读结果按当前主体过滤，total 仅可见范围，创建及改名使用本地事务和回执。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceService {

    /**
     * 分页查询当前操作者可访问的空间，total 只统计可见范围。
     * 按 createdAt 降序、tenantId 和 workspaceId 升序稳定排列；逐项检查当前资格，不提供扫描期间成员变化的目录快照。
     * 每批最多 100 项，授权事实批量读取，摘要复用同一次验证的事实；为精确可见 total 仍扫描全部候选。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param query 分页查询条件
     * @return 可访问空间摘要的分页结果
     */
    PageResult<WorkspaceViews.WorkspaceSummary> listAccessible(TrustedActor actor, PageQuery query);

    /**
     * 按扫描预算浏览当前操作者可访问的空间，不计算可见总数。
     * <p>
     * 按 createdAt 降序、tenantId 和 workspaceId 升序扫描；每批重新验证当前访问资格，
     * 并使用同一次授权验证取得的空间名称进行搜索和生成摘要。
     * 达到结果条数、候选处理数量、批次数或批次间时间预算后返回，结果可能不足请求条数，甚至为空。
     * 是否继续扫描应以返回的 exhausted 和 nextBoundary 为准，不能仅根据结果条数判断。
     * 扫描期间目录和权限可以变化，不提供固定时点的目录快照。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param query 结果条数上限及规范化的空间名称搜索条件
     * @param after 可信的内部候选扫描边界，首批传 null；后续从该边界之后继续，不直接接收外部游标令牌
     * @return 当前可访问且符合搜索条件的空间摘要，以及继续扫描的边界和是否已扫描到候选末尾
     */
    WorkspaceBrowseResult browseAccessible(TrustedActor actor, WorkspaceBrowseQuery query, WorkspaceCandidateKey after);

    /**
     * 显式统计当前操作者可访问的空间数量。
     * <p>
     * 扫描全部成员候选并逐批验证当前访问资格，只累计授权通过的空间，不以候选数量代替可见数量。
     * 此操作不受浏览预算限制，成本随候选数量增长，交互式空间选择器不应自动调用。
     * 统计不应用浏览搜索条件，也不提供固定时点的快照；扫描期间的目录和权限变化可能影响结果。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @return 本次完整扫描中通过当前访问资格验证的空间数量
     */
    long countAccessible(TrustedActor actor);

    /**
     * 查询当前操作者可见的空间公开元数据。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @return 空间公开元数据、当前角色及版本
     */
    WorkspaceViews.WorkspaceView get(TrustedActor actor, Scope scope);

    /**
     * 在指定租户内创建空间，并登记初始负责人。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 空间创建命令，包含租户、名称、描述及命令元数据
     * @return 空间创建回执，包含新空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> create(TrustedActor actor, WorkspaceCommands.CreateWorkspace command);

    /**
     * 更新空间名称及描述，不改变归属、负责人或模型默认配置。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 空间改名命令，包含空间、名称、描述、预期版本及命令元数据
     * @return 空间更新回执，包含更新后的空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> rename(TrustedActor actor, WorkspaceCommands.RenameWorkspace command);
}
