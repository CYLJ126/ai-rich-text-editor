package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 租户成员关系
 * <p>
 * 撤销后保留，重新加入增加代次，不自动退化为访客。
 *
 * @param id         租户成员记录的唯一标识；成员资格撤销后仍保留该记录。
 * @param tenantId   所属租户 ID，与 subjectId 共同确定该主体在租户中的成员关系。
 * @param subjectId  成员的稳定主体 ID，不使用可变登录名。
 * @param role       租户治理角色：OWNER 负责人、ADMIN 管理员、MEMBER 成员或 GUEST 访客；不隐式继承为空间角色或资源访问权。
 * @param state      成员资格状态：ACTIVE 有效、REVOKED 已撤销；授权判断须校验当前状态。
 * @param generation 成员资格代次，非负，首次加入通常为 0，重新加入时递增，用于区分不同轮次的成员资格。
 *                   例如：首次加入为 generation=0，直接主体授权绑定代次 0；退出或被移除后资格失效，
 *                   重新加入变为 generation=1，原代次 0 的授权不会自动恢复，需要重新授权。
 *                   同一轮成员资格中的角色变更不增加代次。
 * @param version    成员记录的乐观锁版本号，初始为 0，每次更新记录时递增，用于版本比较与交换（CAS）；重新加入时继续递增，不重置。
 *                   例如：初始 generation=0、version=0；角色由 MEMBER 改为 ADMIN 后为 generation=0、version=1；
 *                   撤销资格后为 generation=0、version=2；重新加入后为 generation=1、version=3。
 *                   更新时携带预期 version，版本不匹配则拒绝覆盖并发修改；generation 标识资格轮次，version 标识记录更新版本。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TenantMembership(
        String id,
        String tenantId,
        String subjectId,
        TenantRoleEnum role,
        MembershipStateEnum state,
        long generation,
        long version) {

    public TenantMembership {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(tenantId, "tenantId");
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(generation, "generation");
        PlatformChecks.version(version, "version");
    }
}
