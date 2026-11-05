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

    public record Summary(String id, String name, TenantTypeEnum type, TenantStateEnum state,
                          TenantRoleEnum currentRole, long version, boolean isPersonal) {
    }

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

    public record BrowseResult(List<Summary> records, String nextBoundary, boolean exhausted) {
        public BrowseResult {
            records = List.copyOf(records);
            PlatformChecks.require(exhausted == (nextBoundary == null), "invalid tenant browse continuation");
            if (nextBoundary != null) PlatformChecks.id(nextBoundary, "nextBoundary");
        }
    }

    public record CreationOptions(boolean available, boolean organizationAllowed, String reason) {
    }
}
