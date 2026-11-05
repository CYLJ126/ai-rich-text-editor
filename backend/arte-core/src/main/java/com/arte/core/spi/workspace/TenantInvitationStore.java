package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.workspace.TenantInvitation;

import java.util.Optional;

/**
 * 租户邀请独立于空间邀请，写入及锁定读取使用同一租户门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 21:08 ✾
 */
public interface TenantInvitationStore {

    Optional<TenantInvitation> findTenantInvitation(String id);

    Optional<TenantInvitation> lockTenantInvitation(String id);

    boolean insertTenantInvitation(TenantInvitation invitation);

    boolean replaceTenantInvitation(TenantInvitation invitation, long expectedVersion);

    void invalidatePending(String tenantId, String workspaceId, String subjectId);

    PageResult<TenantInvitation> listTenantInvitations(String tenantId, String subjectId, PageQuery query);
}
