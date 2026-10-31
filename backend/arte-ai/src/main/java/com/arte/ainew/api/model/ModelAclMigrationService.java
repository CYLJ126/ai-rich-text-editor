package com.arte.ainew.api.model;

import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 显式且可审计的范围切换应用服务，由宿主可信管理边界调用；不自动迁移或重写原执行及预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public interface ModelAclMigrationService {

    record Migrate(CommandMeta meta, Scope scope, AiEntitlement.Source source, Set<DefinitionRef> bindings,
                   long expectedVersion) {
        public Migrate {
            Objects.requireNonNull(meta);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(source);
            bindings = Set.copyOf(bindings);
            bindings.forEach(reference -> reference.requireType("binding"));
            if (bindings.size() > 256 || expectedVersion < 0) {
                throw new IllegalArgumentException("Invalid model migration manifest");
            }
        }
    }

    /**
     * 当前空间及平台配置管理者读取切换清单，不自动建行或更新版本。
     *
     * @param actor 经宿主可信边界核验、仍须重检当前治理资格的操作者
     * @param scope 要查询的完整租户与空间范围
     * @return 当前清单；空值表示尚未切换，不能把存储故障解释为空值
     */
    Optional<ModelAclScope> get(TrustedActor actor, Scope scope);

    CommandReceipt<ModelAclScope> migrate(TrustedActor actor, Migrate command);
}
