package com.arte.app.platform.ai;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.core.pojo.platform.Scope;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 数据库固定版本控制面
 * <p>
 * 只保存 SecretRef，不保存供应商密钥值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public final class AiControlData {

    private AiControlData() {
    }

    public record BudgetPreset(DefinitionRef rate, Money limit) {
        public BudgetPreset {
            Objects.requireNonNull(rate).requireType("rate");
            Objects.requireNonNull(limit);
        }
    }

    public record Preset(boolean enabled, List<DefinitionRef> bindings, List<BudgetPreset> budgets) {
        public Preset {
            bindings = List.copyOf(bindings);
            budgets = List.copyOf(budgets);
            if (bindings.size() > 100 || budgets.size() > 100 || new HashSet<>(bindings).size() != bindings.size()
                    || budgets.stream().map(BudgetPreset::rate).distinct().count() != budgets.size())
                throw new IllegalArgumentException("Invalid AI preset");
            bindings.forEach(ref -> ref.requireType("binding"));
        }
    }

    public record Catalog(List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                          List<ConnectionDefinition> connections, List<NewAiProperties.Rate> rates,
                          List<NewAiProperties.Grant> legacyGrants, List<NewAiProperties.Budget> legacyBudgets,
                          Preset defaults) {
        public Catalog {
            capabilities = List.copyOf(capabilities);
            bindings = List.copyOf(bindings);
            connections = List.copyOf(connections);
            rates = List.copyOf(rates);
            legacyGrants = List.copyOf(legacyGrants);
            legacyBudgets = List.copyOf(legacyBudgets);
            Objects.requireNonNull(defaults);
        }

        public NewAiProperties properties(NewAiProperties infrastructure, List<NewAiProperties.Grant> grants, List<NewAiProperties.Budget> budgets) {
            return new NewAiProperties(false, infrastructure.dataSourceBean(), infrastructure.releaseRef(), infrastructure.persistence(),
                    infrastructure.limits(), grants, capabilities, bindings, connections, rates, budgets);
        }
    }

    public record WorkspaceConfiguration(Scope scope, Preset preset, long templateVersion, long version) {
    }

    public record Model(DefinitionRef binding, String name, DefinitionRef connection, DefinitionRef rate) {
    }

    public record WorkspaceView(String mode, WorkspaceConfiguration configuration, long workspaceVersion,
                                boolean canManage, boolean needsImport, List<Model> models, Preset defaults,
                                List<NewAiProperties.Rate> toolRates) {
        public WorkspaceView(String mode, WorkspaceConfiguration configuration, long workspaceVersion, boolean canManage, boolean needsImport, List<Model> models, Preset defaults) {
            this(mode, configuration, workspaceVersion, canManage, needsImport, models, defaults, List.of());
        }
    }

    public record CatalogView(long version, Catalog catalog) {
    }
}
