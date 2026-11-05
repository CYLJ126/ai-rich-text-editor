# C11 租户与空间成员治理

## C11-A 组织租户成员

- 在「租户配置 → 目标组织 → 成员」查看成员和已发邀请。负责人可维护管理员与成员、移除成员和交接负责人。管理员可维护普通成员。租户访客可以被移除。
- 通过完整账号查找现有有效用户，再发送组织邀请。受邀人从「租户配置 → 我的邀请」确认接受后取得正式成员资格。工作空间资格仍由各空间单独邀请。
- 移除成员同时撤销其在该租户下的空间成员资格和未接受的邀请。若该成员仍担任某个空间的负责人，须先在对应空间交接。重新加入租户后，各空间仍需重新邀请。

## C11-B 工作空间成员

- 在「工作空间配置 → 目标空间 → 成员」管理成员和已发邀请。负责人可维护管理员、成员与访客，也可将负责人交接给有效管理员或成员。管理员可维护成员与访客。有退出资格的用户在「我的成员资格」确认退出。
- 工作空间邀请可授予成员或访客角色。受邀人在「工作空间配置 →
  我的邀请」确认接受。缺少租户资格时，系统建立仅支撑该空间访问的受限租户资格。接受后刷新可访问目录，进入空间仍须显式点击「进入此空间」。
- 个人托管租户的外部协作使用空间邀请。个人默认空间受负责人交接保护，负责人也不能通过退出或普通角色操作解除自己的资格。
- 负责人交接在同一事务更新原负责人、接任者与父级版本。原负责人转为管理员。普通改角色不授予负责人。

## 接口和回执

所有接口均使用 POST，位于 `/arte/workspace`。读请求取当前账号身份，写请求使用 `Idempotency-Key`
。角色维护、移除、交接和邀请撤销检查目标版本及父级版本。邀请接受检查本人身份、邀请版本及有效期、邀请人的当前权限与范围状态。

| 功能                       | 租户                                                                     | 工作空间                                                                                         |
|----------------------------|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| 资格和完整账号查询         | `getMembershipCapabilities`、`findInvitationTarget`                      | 同左，附 `scope`                                                                                 |
| 成员、已发邀请和本人收件箱 | `listTenantMembers`、`listTenantInvitations`、`listMyTenantInvitations`  | `listWorkspaceMembers`、`listWorkspaceInvitations`、`listMyWorkspaceInvitations`                 |
| 发出、撤销和接受邀请       | `inviteTenantMember`、`revokeTenantInvitation`、`acceptTenantInvitation` | `inviteWorkspaceMember`、`revokeWorkspaceInvitation`、`acceptWorkspaceInvitation`                |
| 角色、移除和交接           | `changeTenantMemberRole`、`removeTenantMember`、`transferTenantOwner`    | `changeWorkspaceMemberRole`、`removeWorkspaceMember`、`transferWorkspaceOwner`、`leaveWorkspace` |

- 邀请默认 7 天，接口支持最长 30 天。已有成员接受时保留当前角色。重新加入增加对应成员代次，已消费的邀请及旧代次授权不会恢复资格。
- 命令、审计和 Outbox 同事务写入。结果不确定时保留会话存储中的原范围、载荷和键。已确认结果可经 `getWorkspaceCommandReceipt`
  再核验，服务端检查当前资格和记录状态。

## 启用及停用

1. 确认目标环境已安装平台身份和工作空间基础表，且平台身份、持久化、工作空间模块已启用。若需要新建组织租户，还需按 C10
   配置组织创建权限与模板。
2. 在目标环境审批后，执行 [邀请表 DDL](backend/arte-app/scripts/arte-platform-membership-invitation-ddl-mysql.sql)
   。可重复执行。邀请表使用同一个 `appDataSource`，以空 `workspace_id` 区分租户邀请。
3. 设置 `arte.platform.membership.enabled=true` 后重启。该能力默认关闭，启用时启动会核验邀请表。
4. 停用时将该开关设为 `false` 后重启，保留已写入的成员资格、邀请和审计数据。成员访问仍由基础授权服务按当前数据判断。

## 验证范围

- 后端定向回归覆盖真实 Spring 装配、HTTP 和 H2。隔离 MySQL 8.4 验证了 DDL 重复安装及两级成员链路，包括本人接受、受限资格、交接、撤权、重新加入和回执。
- 前端定向回归覆盖管理员操作范围、精确账号查询、显式接受和原请求恢复。390 像素的 Chrome
  模拟数据验证了租户发邀、回执核验、空间接受邀请，以及配置目标和当前使用空间分离。
- 业务环境的安装、真实账号验收及多节点验证留待交付阶段。
