package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ModelResourceCommands.ModelKind;
import com.arte.ainew.pojo.model.ModelSelection;
import com.arte.ainew.pojo.model.ReviewedModelPlan;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    public record EgressPolicyView(long version, List<String> origins) {
        public EgressPolicyView {
            origins = List.copyOf(origins);
        }
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
                          List<ConnectionDefinition> connections, List<ControlConfiguration.Rate> rates,
                          List<ControlConfiguration.Grant> legacyGrants,
                          List<ControlConfiguration.Budget> legacyBudgets,
                          Preset defaults, Set<String> disabledConnections, List<ReviewedModelPlan> reviewedPlans) {

        /**
         * 受信内部定义装配入口；审核方案必须显式传入，不能从运行绑定推导。
         */
        public Catalog(List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                       List<ConnectionDefinition> connections, List<ControlConfiguration.Rate> rates,
                       List<ControlConfiguration.Grant> legacyGrants, List<ControlConfiguration.Budget> legacyBudgets,
                       Preset defaults, Set<String> disabledConnections) {
            this(capabilities, bindings, connections, rates, legacyGrants, legacyBudgets, defaults, disabledConnections, List.of());
        }

        public Catalog(List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                       List<ConnectionDefinition> connections, List<ControlConfiguration.Rate> rates,
                       List<ControlConfiguration.Grant> legacyGrants, List<ControlConfiguration.Budget> legacyBudgets, Preset defaults) {
            this(capabilities, bindings, connections, rates, legacyGrants, legacyBudgets, defaults, Set.of());
        }

        public Catalog {
            if (reviewedPlans == null) {
                throw new IllegalArgumentException("AI catalog requires reviewedPlans; legacy catalog payloads must be recreated explicitly");
            }
            reviewedPlans = List.copyOf(reviewedPlans);
            if (reviewedPlans.size() > 256 || reviewedPlans.stream().map(ReviewedModelPlan::planRef).distinct().count() != reviewedPlans.size()) {
                throw new IllegalArgumentException("Invalid reviewed plan directory");
            }
            capabilities = List.copyOf(capabilities);
            bindings = List.copyOf(bindings);
            connections = List.copyOf(connections);
            rates = List.copyOf(rates);
            legacyGrants = List.copyOf(legacyGrants);
            legacyBudgets = List.copyOf(legacyBudgets);
            Objects.requireNonNull(defaults);
            disabledConnections = disabledConnections == null ? Set.of() : Set.copyOf(disabledConnections);
            if (!connections.stream().map(c -> c.definition().id()).collect(java.util.stream.Collectors.toSet()).containsAll(disabledConnections))
                throw new IllegalArgumentException("Unknown disabled connection");
        }

        public static Catalog empty() {
            return new Catalog(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    new Preset(false, List.of(), List.of()));
        }

        public ControlConfiguration properties(NewAiProperties infrastructure, List<ControlConfiguration.Grant> grants, List<ControlConfiguration.Budget> budgets) {
            return new ControlConfiguration(false, infrastructure.releaseRef(),
                    infrastructure.limits(), grants, capabilities, bindings, connections.stream().map(c ->
                    disabledConnections.contains(c.definition().id()) ? new ConnectionDefinition(c.definition(), c.providerId(),
                            c.protocol(), c.endpoint(), c.credential(), ConnectionDefinition.State.DISABLED,
                            c.connectTimeout(), c.responseTimeout(), c.maxResponseBytes()) : c).toList(), rates, budgets);
        }
    }

    /**
     * 目标空间显式启用的实际模型版本，不保存预算规则或个人账户。
     */
    public record WorkspaceConfiguration(Scope scope, boolean enabled, List<ModelSelection> models,
                                         long templateVersion, long version) {

        public WorkspaceConfiguration {
            Objects.requireNonNull(scope);
            models = List.copyOf(models);
            if (models.size() > 100 || models.stream().map(ModelSelection::resourceId).distinct().count() != models.size()
                    || templateVersion < 1 || version < 0) {
                throw new IllegalArgumentException("Invalid workspace model selection");
            }
        }
    }

    /**
     * 独立保存的精确费率批准规则，规则存在不授予模型或凭据使用权。
     */
    public record BudgetRules(Scope scope, List<BudgetPreset> rules, long version) {

        public BudgetRules {
            Objects.requireNonNull(scope);
            rules = new Preset(false, List.of(), rules).budgets();
            if (version < 0) {
                throw new IllegalArgumentException("Invalid budget rule version");
            }
        }
    }

    /**
     * 可读取且允许在目标空间启用的实际模型固定版本，不受本人账户是否已准备影响。
     */
    public record Model(String resourceId, DefinitionRef binding, String name, ModelKind kind,
                        DefinitionRef connection, DefinitionRef rate, boolean enabled,
                        List<String> policyRestrictions) {
    }

    /**
     * 审核目录的安全建议，不含 URL、凭据引用及其他租户策略。
     */
    public record PolicyConnectionOptions(String tenantId, Scope scope, List<String> protocols,
                                          List<String> providers, List<String> hosts) {
    }

    /**
     * 空间模型配置及当前主体允许的治理操作。models 是目标空间内当前主体可读的完整候选集，超出安全边界时拒绝读取。
     */
    public record WorkspaceView(String mode, WorkspaceConfiguration configuration, long workspaceVersion,
                                boolean canManage, boolean needsImport, long templateVersion, List<Model> models,
                                Preset defaults,
                                List<ControlConfiguration.Rate> toolRates) {
    }

    /**
     * 预算规则治理投影，不包含其他主体的预算账户。
     */
    public record BudgetRulesView(Scope scope, BudgetRules configuration, long workspaceVersion, boolean canManage,
                                  boolean canRaiseBudget, List<BudgetPreset> approvedCaps) {
    }

    /**
     * 安全的精确费率候选，保留既有规则使用的历史版本供读取和撤销。
     */
    public record BudgetRateOption(DefinitionRef rate, String kind, String currency, boolean configured,
                                   String inputPerMillion, String outputPerMillion, String toolPerCall) {
    }

    /**
     * 有界安全候选列表；游标绑定当前主体、目标空间及控制目录版本。
     */
    public record ModelOptions(List<Model> items, String nextCursor) {
    }

    /**
     * 有界安全费率列表，不以先启用模型作为查询前置条件。
     */
    public record BudgetRateOptions(List<BudgetRateOption> items, String nextCursor, Scope scope,
                                    long workspaceVersion, Long ruleVersion, long catalogVersion) {
    }

    public record CatalogView(long version, Catalog catalog) {
    }
}
