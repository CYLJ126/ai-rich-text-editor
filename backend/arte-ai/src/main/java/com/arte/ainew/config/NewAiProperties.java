package com.arte.ainew.config;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 新 AI 的受信、固定版本配置；默认关闭，不携带明文凭据，不在启动时写数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new", ignoreUnknownFields = false)
public record NewAiProperties(boolean enabled, String dataSourceBean, String releaseRef,
                              Persistence persistence, Limits limits, List<Grant> grants,
                              List<CapabilityDescriptor> capabilities, List<ResolvedBinding> bindings,
                              List<ConnectionDefinition> connections, List<Rate> rates, List<Budget> budgets) {
    public record Persistence(int threads, int queuedTasks) {
        public Persistence {
            ContractChecks.range(threads, "threads", 1, 64);
            ContractChecks.range(queuedTasks, "queuedTasks", 1, 10_000);
        }
    }

    public record Limits(int maxInputBytes, int maxOutputTokens, long maxOutputBytes,
                         Duration maximumTimeout, Duration snapshotRetention) {
        public Limits {
            ContractChecks.range(maxInputBytes, "maxInputBytes", 1, 1_000_000);
            ContractChecks.range(maxOutputTokens, "maxOutputTokens", 1, 1_000_000);
            ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32 * 1024 * 1024);
            ContractChecks.require(maximumTimeout != null && maximumTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                    && maximumTimeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid maximum timeout");
            ContractChecks.require(snapshotRetention != null && snapshotRetention.compareTo(maximumTimeout) >= 0
                    && snapshotRetention.compareTo(Duration.ofDays(30)) <= 0, "Invalid snapshot retention");
        }
    }

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
     * 单价固定到每百万输入／输出 token；计费实现将在派发阶段接入，未知用量不推导零费用。
     */
    public record Rate(DefinitionRef definition, Money inputPerMillion, Money outputPerMillion) {
        public Rate {
            Objects.requireNonNull(definition).requireType("rate");
            Objects.requireNonNull(inputPerMillion);
            Objects.requireNonNull(outputPerMillion);
            ContractChecks.require(inputPerMillion.currency().equals(outputPerMillion.currency()), "Rate currency mismatch");
        }
    }

    public record Budget(String budgetRef, ExecutionOwner owner, Money limit, DefinitionRef rate) {
        public Budget {
            ContractChecks.id(budgetRef, "budgetRef");
            Objects.requireNonNull(owner);
            Objects.requireNonNull(limit);
            Objects.requireNonNull(rate).requireType("rate");
        }
    }

    public NewAiProperties {
        dataSourceBean = dataSourceBean == null ? "appDataSource" : ContractChecks.id(dataSourceBean, "dataSourceBean");
        persistence = persistence == null ? new Persistence(4, 256) : persistence;
        limits = limits == null ? new Limits(65_536, 4096, 1_048_576, Duration.ofMinutes(2), Duration.ofMinutes(15)) : limits;
        grants = copy(grants);
        capabilities = copy(capabilities);
        bindings = copy(bindings);
        connections = copy(connections);
        rates = copy(rates);
        budgets = copy(budgets);
        if (enabled) {
            ContractChecks.id(releaseRef, "releaseRef");
            ContractChecks.require(!grants.isEmpty() && !capabilities.isEmpty() && !bindings.isEmpty()
                    && !connections.isEmpty() && !rates.isEmpty() && !budgets.isEmpty(), "Enabled admission requires complete fixed configuration");
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : ContractChecks.list(values, "configuration", 0, 256);
    }
}
