package com.arte.core.pojo.access;

import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 授权管理返回对象
 * <p>
 * 角色／代次等内部匹配细节不作为永久执行许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class ResourceAccessViews {

    private ResourceAccessViews() {
    }

    /**
     * 管理者可见的单条授权视图
     * <p>
     * 展示授权目标、允许动作、状态及来源；记录有效不代表当前访问一定获准，仍需统一授权判定。
     *
     * @param id        授权记录唯一标识，用于定位待撤销的授权。
     * @param resource  被授权资源的引用，包含租户、资源类型和资源标识。
     * @param recipient 授权目标：指定主体、空间角色或整个空间；不暴露内部成员代次。
     * @param action    本条授权允许的单个资源动作。
     * @param state     授权记录状态：有效或已撤销；不替代期限、成员资格及政策检查。
     * @param expiresAt 授权到期时间；为空表示不设到期时间。
     * @param source    授权来源，用于区分创建、分享或管理等独立授权。
     * @param version   授权记录的非负版本号，撤销时用于版本比较与交换（CAS）。
     */
    public record GrantView(
            String id, ResourceRef resource, GrantRecipient recipient, Action action,
            GrantStateEnum state, Instant expiresAt, GrantSourceEnum source, long version) {

        public GrantView {
            PlatformChecks.id(id, "id");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(source, "source");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 一次多动作授权操作的结果
     * <p>
     * 返回本次产生的逐项授权记录及操作完成后的资源 ACL 版本。
     *
     * @param grants     本次授予产生的授权视图列表；构造时复制为不可变列表。
     * @param aclVersion 本次操作完成后的非负 ACL 版本号，后续授权变更使用该版本进行并发校验。
     */
    public record GrantResult(List<GrantView> grants, long aclVersion) {

        public GrantResult {
            grants = List.copyOf(grants);
            PlatformChecks.version(aclVersion, "aclVersion");
        }
    }

    /**
     * 资源访问配置视图
     * <p>
     * 展示资源归属、访问模式及 ACL 版本，不包含正文或某个主体的最终权限结论。
     *
     * @param resource   资源定位引用，包含租户、资源类型和资源标识。
     * @param ownership  资源的租户或空间归属，须与领域权威归属一致。
     * @param visibility 访问模式：EXPLICIT 使用具体授权，SPACE_SHARED 允许匹配明确登记的空间或角色授权。
     * @param aclVersion 资源 ACL 的非负版本号，用于授权或访问模式变更时的版本比较与交换（CAS）。
     */
    public record AccessView(ResourceRef resource, ResourceOwnership ownership,
                             ResourceVisibilityEnum visibility, long aclVersion) {

        public AccessView {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(ownership, "ownership");
            Objects.requireNonNull(visibility, "visibility");
            PlatformChecks.version(aclVersion, "aclVersion");
        }
    }
}
