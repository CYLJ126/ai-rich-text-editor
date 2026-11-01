package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.access.ResourceOwnership;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 稳定模型管理身份
 * <p>
 * 归属、创建者独立于凭据责任、发布引用及 ExecutionOwner。
 *
 * @param resource  模型管理资源的稳定引用，包含租户、model 类型及服务器生成的资源 ID；关联新发布版本时沿用，不以 bindingRef 作为管理身份。
 * @param ownership 资源的租户或空间归属，租户须与 resource 一致；归属不表示具体主体已获使用资格。
 * @param name      模型管理展示名称，经名称规则校验；独立于发送给供应商的实际模型标识。
 * @param createdBy 创建资源的稳定主体 ID，仅记录创建事实；不作为资源所有者、凭据责任主体或执行归属，也不据此动态推算权限。
 * @param state     当前管理状态：DRAFT 为未关联发布版本的草稿，PUBLISHED 为已关联发布版本，DISABLED 为已停用；发布状态本身不授予执行资格。
 * @param bindingId 首次关联时确定的绑定族 ID，不包含版本；草稿时为空，关联后保持稳定，同族的多个精确发布版本分别保存为 ModelResourceVersion。
 * @param version   管理资源的非负乐观锁版本，初始为 0；关联新发布版本或停用时递增，用于 CAS，与公共 ACL 版本及 binding 的字符串版本独立。
 * @param createdAt 资源创建时的时间点，由服务端生成，当前创建流程按 UTC 微秒精度记录；后续发布或停用不改写。
 * @param managementScope 固定管理空间；租户模型的草稿及凭据在此维护，实际使用范围独立保存。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public record ModelResource(ResourceRef resource, ResourceOwnership ownership, String name, String createdBy,
                            State state, String bindingId, long version, Instant createdAt,
                            com.arte.core.pojo.platform.Scope managementScope) {

    /**
     * 兼容原空间模型及既有 JSON；租户模型必须明确管理空间。
     */
    public ModelResource(ResourceRef resource, ResourceOwnership ownership, String name, String createdBy,
                         State state, String bindingId, long version, Instant createdAt) {
        this(resource, ownership, name, createdBy, state, bindingId, version, createdAt, null);
    }

    public enum State {DRAFT, PUBLISHED, DISABLED}

    public ModelResource {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(ownership);
        if (managementScope == null) {
            managementScope = new com.arte.core.pojo.platform.Scope(ownership.tenantId(), ownership.workspaceId());
        }
        PlatformChecks.require(managementScope.tenantId().equals(ownership.tenantId())
                && (ownership.workspaceId() == null || ownership.workspaceId().equals(managementScope.workspaceId())), "Invalid model management scope");
        Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt);
        PlatformChecks.require(resource.type().equals(MODEL) && resource.tenantId().equals(ownership.tenantId()), "Invalid model ownership");
        name = PlatformChecks.name(name);
        PlatformChecks.id(createdBy, "createdBy");
        PlatformChecks.version(version, "version");
        if (bindingId != null) PlatformChecks.id(bindingId, "bindingId");
        PlatformChecks.require(state == State.DRAFT ? bindingId == null : bindingId != null, "Published identity needs binding family");
    }

    public ResourceDescriptor descriptor() {
        return new ResourceDescriptor(resource, ownership, state.name(), version, createdBy);
    }
}
