package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantTypeEnum;

/**
 * 租户目录仅使用通过当前资格校验后的名称和类型进行筛选。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public record TenantBrowseQuery(int size, String search, TenantTypeEnum type) {

    public TenantBrowseQuery {
        search = new WorkspaceBrowseQuery(size, search).search();
    }
}
