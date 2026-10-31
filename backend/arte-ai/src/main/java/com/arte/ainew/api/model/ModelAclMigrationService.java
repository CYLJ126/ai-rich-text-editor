package com.arte.ainew.api.model;

import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Objects;
import java.util.Set;

/**
 * 内部、显式且可审计的范围切换；不自动迁移或重写原执行及预算。
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

    CommandReceipt<ModelAclScope> migrate(TrustedActor actor, Migrate command);
}
