package com.arte.core.api.workspace;

import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantBrowseQuery;
import com.arte.core.pojo.workspace.TenantDirectoryViews;

/**
 * 租户管理的只读能力
 * <p>
 * 为租户配置提供只读查询，包括浏览当前操作者可见的租户、查看租户详情和查询组织租户创建资格。
 * 目录和详情基于当前操作者的有效正式租户成员资格生成。创建组织租户及更新租户信息由 {@link TenantGovernanceService} 执行，并在提交时校验资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public interface TenantQueryService {

    /**
     * 按租户 ID 升序浏览当前操作者可见且符合筛选条件的租户。
     * <p>
     * 每批重新校验访问资格。扫描受候选数量、批次数和时间预算限制，返回的摘要可能不足请求条数或为空。
     * 使用 {@code exhausted} 判断是否扫完，未扫完时使用 {@code nextBoundary} 继续。
     *
     * @param actor         经可信认证适配确认的当前操作者
     * @param query         单次结果条数上限、名称搜索和可选的租户类型筛选条件
     * @param afterTenantId 内部扫描边界，从该租户 ID 之后继续，首次查询传 {@code null}
     * @return 本次扫描得到的可见租户摘要、续扫边界及候选是否已耗尽
     */
    TenantDirectoryViews.BrowseResult browseAccessible(TrustedActor actor, TenantBrowseQuery query, String afterTenantId);

    /**
     * 查询指定租户的可见详情，并按当前资格计算可展示的视图级别和操作。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param tenantId 目标租户 ID
     * @return 租户基本信息、操作者的租户角色、记录版本、视图级别和操作标识
     * @throws PlatformException 目标租户不存在或对当前操作者不可见时，错误码为 {@code NOT_FOUND}
     */
    TenantDirectoryViews.Detail getTenant(TrustedActor actor, String tenantId);

    /**
     * 查询组织租户创建能力是否可用，以及当前操作者是否具备创建资格。
     * 查询结果供界面展示，提交创建命令时会重新校验。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @return 创建能力的可用状态、操作者的创建资格及判断原因码
     */
    TenantDirectoryViews.CreationOptions getCreationOptions(TrustedActor actor);
}
