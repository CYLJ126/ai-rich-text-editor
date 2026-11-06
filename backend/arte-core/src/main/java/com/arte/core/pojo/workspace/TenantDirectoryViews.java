package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 租户读取的对外投影，不包含内部授权、个人关联主体或政策内容。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public final class TenantDirectoryViews {

    private TenantDirectoryViews() {
    }

    /**
     * 租户目录中当前主体可见的列表项。
     *
     * @param id          租户 ID
     * @param name        展示名称
     * @param type        租户类型
     * @param state       租户当前生命周期状态
     * @param currentRole 当前主体在该范围内的角色
     * @param version     租户记录版本
     * @param isPersonal  是否为个人租户
     */
    public record Summary(String id, String name, TenantTypeEnum type, TenantStateEnum state,
                          TenantRoleEnum currentRole, long version, boolean isPersonal) {
    }

    /**
     * 展示指定租户的详情及当前主体可用的操作。
     *
     * @param id 租户 ID
     * @param name 展示名称
     * @param type 租户类型
     * @param state 租户当前生命周期状态
     * @param currentRole 当前主体在该范围内的角色
     * @param version 租户记录版本
     * @param isPersonal 是否为个人租户
     * @param viewLevel 当前主体可见的视图级别，分为基础和治理级别
     * @param actions 按当前资格计算的操作标识列表
     */
    public record Detail(String id, String name, TenantTypeEnum type, TenantStateEnum state,
                         TenantRoleEnum currentRole, long version, boolean isPersonal,
                         ViewLevel viewLevel, List<String> actions) {
        public Detail {
            actions = List.copyOf(actions);
        }
    }

    public enum ViewLevel {
        BASIC, GOVERNANCE
    }

    /**
     * 返回租户目录的本次浏览结果及后续扫描边界。
     *
     * @param records 本次扫描返回的可见租户摘要
     * @param nextBoundary 下一次扫描的起始边界，取最后扫描的候选租户 ID，扫描完毕时为 {@code null}
     * @param exhausted 是否已扫描到候选尾部
     */
    public record BrowseResult(List<Summary> records, String nextBoundary, boolean exhausted) {
        public BrowseResult {
            records = List.copyOf(records);
            PlatformChecks.require(exhausted == (nextBoundary == null), "invalid tenant browse continuation");
            if (nextBoundary != null) PlatformChecks.id(nextBoundary, "nextBoundary");
        }
    }

    /**
     * 展示组织租户创建能力的可用性和当前主体的创建资格。
     *
     * @param available 组织租户创建能力是否已启用
     * @param organizationAllowed 当前主体是否获准创建组织租户
     * @param reason 创建能力的判断原因码
     */
    public record CreationOptions(boolean available, boolean organizationAllowed, String reason) {
    }
}
