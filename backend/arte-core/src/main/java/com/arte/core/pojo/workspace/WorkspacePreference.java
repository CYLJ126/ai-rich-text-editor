package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;
import java.util.Set;

/**
 * 用户的工作空间偏好
 * <p>
 * 用户偏好，不授权、不保存当前标签上下文。defaultScope 是初始化关联，普通偏好命令不能修改。
 * 偏好用于导航，不授予空间访问权限，也不改变当前浏览器标签中已有操作的空间范围。读取时会检查当前访问资格，对失效引用进行回退或过滤。
 *
 * @param subjectId    偏好所属用户的稳定主体标识，按主体保存，与具体租户或空间的成员关系独立。
 * @param defaultScope 系统初始化时关联的个人默认空间，不能为空，普通偏好更新不得修改；用于默认空间保护及导航回退，不授予访问权限。
 * @param startupScope 用户期望启动时进入的空间，可为空；读取偏好视图时若该空间已不可访问，则回退为个人默认空间。
 * @param lastScope    用户上次访问的空间，可为空，仅作后续导航提示；读取偏好视图时若已不可访问则返回空，不覆盖当前标签内的操作范围。
 * @param favorites    用户收藏的空间集合，保存为不含空元素的不可变集合；更新服务最多接受 100 项，并在读取偏好视图时过滤已不可访问的空间。
 * @param version      非负的偏好记录版本号，初始化为 0，更新时递增，用于版本比较与交换（CAS），独立于空间自身版本。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspacePreference(
        String subjectId,
        Scope defaultScope,
        Scope startupScope,
        Scope lastScope,
        Set<Scope> favorites,
        long version) {

    public WorkspacePreference {
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(defaultScope, "defaultScope");
        favorites = Set.copyOf(favorites);
        PlatformChecks.version(version, "version");
    }
}
