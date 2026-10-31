package com.arte.app.web.platform;

import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 稳定模型管理 HTTP 契约
 * <p>
 * 不接收发布内容、所有者、创建者或凭据，不输出内部连接快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
public final class ModelResourcePayload {

    private ModelResourcePayload() {
    }

    public record CreateRequest(@NotNull Scope scope,
                                @NotBlank @Size(max = 100) String name) implements StrictPlatformRequest {
    }

    public record GetRequest(@NotNull Scope scope,
                             @NotBlank @Size(max = 256) String resourceId) implements StrictPlatformRequest {
    }

    public record VersionsRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                  @Min(1) Long current, @Min(1) @Max(100) Long size) implements StrictPlatformRequest {
        public VersionsRequest {
            current = current == null ? 1L : current;
            size = size == null ? 20L : size;
        }
    }

    public record AssociateRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                   @NotNull @Min(0) Long expectedVersion, @NotNull DefinitionRef binding,
                                   @NotBlank @Pattern(regexp = "FIXED|DATABASE") String source,
                                   @Min(1) Long catalogRevision) implements StrictPlatformRequest {
    }

    public record DisableRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                 @NotNull @Min(0) Long expectedVersion) implements StrictPlatformRequest {
    }

    public record ScopeRequest(@NotNull Scope scope) implements StrictPlatformRequest {
    }

    /**
     * 旧目录完整清单更新；首次版本 0 显式激活，空集合关闭旧目录候选并保持 ACL 模式。
     * 个人发布使用独立公共资格及空间 AI 门控，不通过此清单转交个人凭据用途。
     */
    public record UpdateScopeRequest(@NotNull Scope scope, @NotNull @Min(0) Long expectedVersion,
                                     @NotBlank @Pattern(regexp = "FIXED|DATABASE") String source,
                                     @NotNull @Size(max = 256) List<@NotNull DefinitionRef> bindings) implements StrictPlatformRequest {
        public Set<DefinitionRef> bindingSet() {
            PlatformChecks.require(new HashSet<>(bindings).size() == bindings.size(), "Duplicate binding reference");
            return Set.copyOf(bindings);
        }
    }

    public record Resource(String resourceId, Scope scope, String name, String createdBy, String state,
                           String bindingId, long version, Instant createdAt) {
        public static Resource from(ModelResource value) {
            return new Resource(value.resource().resourceId(), new Scope(value.ownership().tenantId(), value.ownership().workspaceId()),
                    value.name(), value.createdBy(), value.state().name(), value.bindingId(), value.version(), value.createdAt());
        }
    }

    /**
     * 只公开模型发布定位与业务元数据；不含 endpoint、credential、内部 target 或授权证明。
     */
    public record Version(String resourceId, DefinitionRef binding, DefinitionRef capability, DefinitionRef connection,
                          String providerId, String remoteOperation, Long contextWindowTokens, DefinitionRef rate,
                          String source, Long catalogRevision, String releaseRef, String linkedBy, Instant linkedAt) {
        public static Version from(ModelResourceVersion value) {
            ModelPublication publication = value.publication();
            return new Version(value.resource().resourceId(), publication.binding().definition(), publication.binding().capability().definition(),
                    publication.connection().definition(), publication.connection().providerId(), publication.binding().remoteOperation(),
                    publication.binding().contextWindowTokens(), publication.rate().definition(), publication.source().name(), publication.catalogRevision(),
                    publication.releaseRef(), value.linkedBy(), value.linkedAt());
        }
    }

    public record AclScope(Scope scope, boolean migrated, String source, List<DefinitionRef> bindings, long version,
                           String changedBy, Instant changedAt) {
        public static AclScope from(ModelAclScope value) {
            return new AclScope(value.scope(), true, value.configurationSource().name(), value.bindings().stream()
                    .sorted(Comparator.comparing(DefinitionRef::id).thenComparing(DefinitionRef::version)).toList(),
                    value.version(), value.changedBy(), value.changedAt());
        }

        public static AclScope absent(Scope scope) {
            return new AclScope(scope, false, null, List.of(), 0, null, null);
        }
    }

    public static ResourceRef ref(Scope scope, String resourceId) {
        return new ResourceRef(scope.tenantId(), MODEL, resourceId);
    }
}
