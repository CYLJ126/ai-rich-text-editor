package com.arte.core.pojo.access;

import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 单个资源的访问控制总记录（ACL 根）
 * <p>
 * 资源的公共访问控制（ACL）根记录，统一保存资源定位、归属、访问模式及 ACL 版本。
 * 具体授权目标和允许动作由独立的{@link ResourceGrant}记录表达，本对象不直接表示某个主体具有访问权限。
 * 归属须与领域事实一致，不能用 ACL 镜像覆盖领域归属。
 * ResourceAccess 保存资源整体的访问设置，ResourceGrant 保存授予谁、允许什么动作等具体授权。最终能否操作还需统一授权服务判断，不能仅凭归属或共享模式放行。
 *
 * @param resource 资源定位引用，包含租户、资源类型和资源标识。
 * @param ownership 资源的租户或空间归属，须与领域权威归属一致，且租户必须与 resource 一致。
 * @param visibility 资源访问模式：EXPLICIT 按具体授权，SPACE_SHARED 按明确登记的空间或角色动作授权，不自动授予全部成员完全控制权。
 * @param aclVersion 非负的资源 ACL 版本号，用于授权变更时的版本比较与交换（CAS）及授权依据校验。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceAccess(
        ResourceRef resource,
        ResourceOwnership ownership,
        ResourceVisibilityEnum visibility,
        long aclVersion) {

    public ResourceAccess {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(visibility, "visibility");
        PlatformChecks.version(aclVersion, "aclVersion");
        PlatformChecks.require(resource.tenantId().equals(ownership.tenantId()), "resource and ownership tenant mismatch");
    }
}
