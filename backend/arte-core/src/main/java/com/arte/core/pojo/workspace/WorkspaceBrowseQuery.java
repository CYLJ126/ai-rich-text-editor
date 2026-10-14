package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Locale;

/**
 * 空间目录浏览条件
 * 搜索仅应用于当前授权验证取得的空间名称，不使用未经验证的候选名称快照。
 *
 * @param size   单次返回的空间摘要条数上限，范围为 1～100；受扫描预算及当前访问资格影响，实际结果可能更少
 * @param search 空间名称的包含匹配搜索词；null 视为空字符串，去除首尾空白后校验长度不超过 100 个 UTF-16 代码单元且不含控制字符，
 *               再按 Locale.ROOT 转为小写；空字符串表示不筛选名称，转小写后的长度可能增长
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 12:35 ✾
 */
public record WorkspaceBrowseQuery(int size, String search) {

    public WorkspaceBrowseQuery {
        PlatformChecks.require(size >= 1 && size <= 100, "invalid browse size");
        search = search == null ? "" : search.strip();
        PlatformChecks.require(search.length() <= 100 && search.chars().noneMatch(Character::isISOControl), "invalid search");
        search = search.toLowerCase(Locale.ROOT);
    }
}
