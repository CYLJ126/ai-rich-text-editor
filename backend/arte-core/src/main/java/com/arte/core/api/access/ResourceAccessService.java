package com.arte.core.api.access;

import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.access.ResourceAccessViews;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;

/**
 * 授权管理 API
 * <p>
 * 统一调用 AuthorizationService，普通 share 不授予分享、治理、凭据或预算管理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ResourceAccessService {

    /**
     * 分页查询当前操作者可管理的资源授权记录。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param scope    当前选择的租户与工作空间范围，仍需服务端验证
     * @param resource 待查询授权的资源引用
     * @param query    分页查询条件
     * @return 可管理的授权记录分页结果
     */
    PageResult<ResourceAccessViews.GrantView> listGrants(TrustedActor actor, Scope scope, ResourceRef resource, PageQuery query);

    /**
     * 在当前权限及政策允许范围内授予资源动作。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 授权命令，包含资源、目标、动作、期限、预期 ACL 版本及命令元数据
     * @return 授权操作回执，包含逐项授权结果及 ACL 版本
     */
    CommandReceipt<ResourceAccessViews.GrantResult> grant(TrustedActor actor, ResourceAccessCommands.GrantResource command);

    /**
     * 撤销指定来源的资源授权，保留其他独立授权来源。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 撤销命令，包含资源、授权 ID、预期 ACL 与授权版本及命令元数据
     * @return 撤销操作回执，业务结果为空
     */
    CommandReceipt<Void> revoke(TrustedActor actor, ResourceAccessCommands.RevokeGrant command);

    /**
     * 更新资源访问模式，共享时需明确指定允许动作。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 模式更新命令，包含资源、访问模式、共享动作、预期 ACL 版本及命令元数据
     * @return 模式更新回执，包含资源访问配置及 ACL 版本
     */
    CommandReceipt<ResourceAccessViews.AccessView> changeVisibility(TrustedActor actor, ResourceAccessCommands.ChangeVisibility command);
}
