package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantTypeEnum;

/**
 * 定义租户目录的浏览条件。名称和类型筛选应用于已通过当前访问资格校验的租户。
 *
 * @param size 单次返回的租户摘要条数上限，范围为 1～100
 * @param search 租户名称的包含匹配搜索词，去除首尾空白并转为小写，空字符串表示不限名称
 * @param type 可选的租户类型筛选条件，为 {@code null} 时不限类型
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public record TenantBrowseQuery(int size, String search, TenantTypeEnum type) {

    public TenantBrowseQuery {
        search = new WorkspaceBrowseQuery(size, search).search();
    }
}
