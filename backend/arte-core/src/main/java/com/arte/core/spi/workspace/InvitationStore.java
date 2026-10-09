package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.workspace.WorkspaceInvitation;

import java.util.Optional;

/**
 * 邀请定位后在治理门控事务内重读并原子接受。已消费邀请不能重激活成员，目标、期限、邀请者资格由公共规则重检。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface InvitationStore {

    /**
     * 按邀请 ID 查询邀请记录，不存在时返回空。
     */
    Optional<WorkspaceInvitation> find(String invitationId);

    /**
     * 在当前事务内锁定并读取邀请记录。
     */
    Optional<WorkspaceInvitation> lock(String invitationId);

    /**
     * 新增邀请记录，唯一条件冲突时返回 false。
     */
    boolean insert(WorkspaceInvitation invitation);

    /**
     * 按预期版本更新邀请记录，版本条件不满足时返回 false。
     */
    boolean replace(WorkspaceInvitation invitation, long expectedVersion);

    /**
     * 分页查询以指定主体为邀请目标的记录。
     */
    PageResult<WorkspaceInvitation> listForSubject(String subjectId, PageQuery query);
}
