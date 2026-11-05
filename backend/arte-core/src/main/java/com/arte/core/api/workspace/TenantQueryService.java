package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantBrowseQuery;
import com.arte.core.pojo.workspace.TenantDirectoryViews;

/**
 * 租户管理的只读能力；提交类操作由独立用例校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public interface TenantQueryService {

    TenantDirectoryViews.BrowseResult browseAccessible(TrustedActor actor, TenantBrowseQuery query, String afterTenantId);

    TenantDirectoryViews.Detail getTenant(TrustedActor actor, String tenantId);

    TenantDirectoryViews.CreationOptions getCreationOptions(TrustedActor actor);
}
