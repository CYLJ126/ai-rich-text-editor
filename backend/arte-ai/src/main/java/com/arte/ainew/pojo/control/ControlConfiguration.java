package com.arte.ainew.pojo.control;

import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 数据库控制面的可信配置快照。只由服务端来源生成，不绑定部署配置或客户端输入。
 *
 * @param enabled      当前控制快照是否允许新调用
 * @param releaseRef   精确控制发布标识
 * @param limits       当前范围的执行限制，实际使用时仍受部署上限约束
 * @param grants       当前主体及范围的授权事实
 * @param capabilities 精确版本的能力定义
 * @param bindings     精确版本的模型绑定
 * @param connections  精确版本的连接定义
 * @param rates        精确版本的费率
 * @param budgets      当前执行归属的预算定义
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 11:40 ✾
 */
public record ControlConfiguration(boolean enabled, String releaseRef, NewAiProperties.Limits limits,
                                   List<Grant> grants, List<CapabilityDescriptor> capabilities,
                                   List<ResolvedBinding> bindings, List<ConnectionDefinition> connections,
                                   List<Rate> rates, List<Budget> budgets) {

    public ControlConfiguration {
        ContractChecks.id(releaseRef, "releaseRef");
        limits = limits == null ? NewAiProperties.defaultLimits() : limits;
        grants = copy(grants);
        capabilities = copy(capabilities);
        bindings = copy(bindings);
        connections = copy(connections);
        rates = copy(rates);
        budgets = copy(budgets);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : ContractChecks.list(values, "configuration", 0, 256);
    }

    /**
     * 描述一个可信主体在指定租户／空间内可执行的操作及可使用的模型绑定和预算范围。
     *
     * @param subjectName   用于受信身份匹配的主体名称，用户主体通常为登录名
     * @param subjectId     主体的稳定标识，用于执行归属及身份一致性校验
     * @param principalKind 主体类型，例如 USER 或 SERVICE
     * @param tenantId      本授权适用的租户标识
     * @param workspaceId   本授权适用的工作空间标识
     * @param grantRef      本授权的依据／版本标识，用于记录和复核授权来源
     * @param enabled       本授权是否有效，停用后不允许据此取得操作资格
     * @param scopes        允许的操作权限集合，例如 ai:invoke、ai:read；请求权限必须包含于此集合
     * @param bindingIds    允许使用的模型绑定 ID 集合，此处保存 ID，精确版本仍由模型资格及来源配置核验
     * @param budgetRefs    允许访问或使用的预算引用集合，具体操作还须满足对应 scope 及预算归属要求
     */
    public record Grant(String subjectName, String subjectId, ExecutionPrincipal.Kind principalKind,
                        String tenantId, String workspaceId, String grantRef, boolean enabled,
                        Set<String> scopes, Set<String> bindingIds, Set<String> budgetRefs) {

        public Grant {
            ContractChecks.id(subjectName, "subjectName");
            ContractChecks.id(subjectId, "subjectId");
            Objects.requireNonNull(principalKind);
            new ExecutionOwner(tenantId, workspaceId, subjectId);
            ContractChecks.id(grantRef, "grantRef");
            scopes = ids(scopes, "scopes");
            bindingIds = ids(bindingIds, "bindingIds");
            budgetRefs = ids(budgetRefs, "budgetRefs");
        }

        private static Set<String> ids(Set<String> values, String field) {
            var copied = ContractChecks.set(values, field, 256);
            copied.forEach(value -> ContractChecks.id(value, field));
            return copied;
        }
    }

    /**
     * 定义固定版本的模型 Token 费率或工具按次费率，两种计价方式互斥。
     *
     * @param definition       固定版本的费率引用，类型必须为 rate
     * @param inputPerMillion  模型每百万输入 Token 的价格及币种；模型费率必填，工具费率为空
     * @param outputPerMillion 模型每百万输出 Token 的价格及币种，须与输入价格同币种；工具费率为空
     * @param toolPerCall      工具每次调用的价格及币种；非空时输入／输出 Token 价格必须为空，模型费率中此字段为空
     */
    public record Rate(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Money toolPerCall) {
        public Rate(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion) {
            this(definition, inputPerMillion, outputPerMillion, null);
        }

        public java.util.Currency currency() {
            return toolPerCall == null ? inputPerMillion.currency() : toolPerCall.currency();
        }

        public Rate {
            Objects.requireNonNull(definition).requireType("rate");
            if (toolPerCall == null) {
                Objects.requireNonNull(inputPerMillion);
                Objects.requireNonNull(outputPerMillion);
                ContractChecks.require(inputPerMillion.currency().equals(outputPerMillion.currency()), "Rate currency mismatch");
            } else
                ContractChecks.require(inputPerMillion == null && outputPerMillion == null, "Tool rate must not contain Token prices");
        }
    }

    /**
     * 定义指定执行归属下预算账户的配置额度及关联费率，供预算资格校验和账户初始化使用。
     *
     * @param budgetRef 预算定义的稳定引用，用于定位对应账户
     * @param owner     预算归属，包含租户、工作空间及稳定主体标识
     * @param limit     配置的预算额度及币种，不代表账户当前可用余额，也不用于重置已有账户
     * @param rate      关联的固定版本费率引用，类型必须为 rate，使用时须核对费率及币种兼容性
     */
    public record Budget(String budgetRef, ExecutionOwner owner, Money limit, DefinitionRef rate) {
        public Budget {
            ContractChecks.id(budgetRef, "budgetRef");
            Objects.requireNonNull(owner);
            Objects.requireNonNull(limit);
            Objects.requireNonNull(rate).requireType("rate");
        }
    }

}
