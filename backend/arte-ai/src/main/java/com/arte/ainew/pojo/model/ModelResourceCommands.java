package com.arte.ainew.pojo.model;

import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 管理命令只接受定位，不接受自报所有者、创建者、凭据提供者或发布快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public final class ModelResourceCommands {

    private ModelResourceCommands() {
    }

    /**
     * 创建时固定的合法模型预设；归属和凭据种类由服务端推导。
     */
    public enum ModelKind {
        // 管理空间归属，使用个人凭据。
        PERSONAL,
        // 当前租户归属，使用租户凭据。
        TENANT
    }

    public record Create(CommandMeta meta, Scope scope, String name, ModelKind modelKind) {
        public Create(CommandMeta meta, Scope scope, String name) {
            this(meta, scope, name, ModelKind.PERSONAL);
        }
        public Create {
            Objects.requireNonNull(meta);
            Objects.requireNonNull(scope);
            name = PlatformChecks.name(name);
            Objects.requireNonNull(modelKind);
        }
    }

    public record Associate(CommandMeta meta, Scope scope, ResourceRef resource, long expectedVersion,
                            DefinitionRef binding, ModelPublication.Source source, Long catalogRevision) {
        public Associate {
            Objects.requireNonNull(meta);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(source);
            Objects.requireNonNull(binding).requireType("binding");
            PlatformChecks.version(expectedVersion, "expectedVersion");
            PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "Cross-tenant association unsupported");
            PlatformChecks.require(source == ModelPublication.Source.DATABASE ? catalogRevision != null && catalogRevision > 0 : catalogRevision == null, "Exact source revision required");
        }
    }

    public record Disable(CommandMeta meta, Scope scope, ResourceRef resource, long expectedVersion) {
        public Disable {
            Objects.requireNonNull(meta);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(resource);
            PlatformChecks.version(expectedVersion, "expectedVersion");
        }
    }
}
