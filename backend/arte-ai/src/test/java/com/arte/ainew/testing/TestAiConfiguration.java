package com.arte.ainew.testing;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;

import java.util.List;

/**
 * 测试数据与部署参数的组合；仅在测试中提供内存受信来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 11:25 ✾
 */
public record TestAiConfiguration(boolean enabled, String dataSourceBean, String releaseRef,
                                  NewAiProperties.Persistence persistence, NewAiProperties.Limits limits,
                                  List<ControlConfiguration.Grant> grants, List<CapabilityDescriptor> capabilities,
                                  List<ResolvedBinding> bindings, List<ConnectionDefinition> connections,
                                  List<ControlConfiguration.Rate> rates, List<ControlConfiguration.Budget> budgets) {
    public TestAiConfiguration {
        dataSourceBean = dataSourceBean == null ? "appDataSource" : dataSourceBean;
        persistence = persistence == null ? new NewAiProperties.Persistence(4, 256) : persistence;
        limits = limits == null ? NewAiProperties.defaultLimits() : limits;
        grants = grants == null ? List.of() : List.copyOf(grants);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        connections = connections == null ? List.of() : List.copyOf(connections);
        rates = rates == null ? List.of() : List.copyOf(rates);
        budgets = budgets == null ? List.of() : List.copyOf(budgets);
    }

    public NewAiProperties deployment() {
        return new NewAiProperties(enabled, dataSourceBean, releaseRef, persistence, limits);
    }

    public ControlConfiguration snapshot() {
        return new ControlConfiguration(enabled, releaseRef == null ? "test-release" : releaseRef, limits,
                grants, capabilities, bindings, connections, rates, budgets);
    }

    private static final java.util.Map<TestAiConfiguration, AiControlConfigurationSource> SOURCES = new java.util.IdentityHashMap<>();
    private static final java.util.Map<TestAiConfiguration, com.arte.ainew.spi.auth.ExecutionAuthorizationResolver> CHECKS = new java.util.IdentityHashMap<>();

    public synchronized AiControlConfigurationSource source(com.arte.ainew.spi.auth.ExecutionAuthorizationResolver resolver) {
        synchronized (SOURCES) {
            CHECKS.put(this, resolver);
            return source();
        }
    }

    public AiControlConfigurationSource source() {
        synchronized (SOURCES) {
            return SOURCES.computeIfAbsent(this, ignored -> createSource());
        }
    }

    private AiControlConfigurationSource createSource() {
        return new AiControlConfigurationSource() {
            public ControlConfiguration configuration(ExecutionContext context) {
                com.arte.ainew.spi.auth.ExecutionAuthorizationResolver check;
                synchronized (SOURCES) {
                    check = CHECKS.get(TestAiConfiguration.this);
                }
                if (check == null) check = new TestAuthorizationResolver(TestAiConfiguration.this);
                var original = context.authorization();
                var current = check.resolve(original.principal().subjectName(), original.tenantId(), original.workspaceId(), original.scopes()).block();
                if (!original.equals(current))
                    throw new org.springframework.security.access.AccessDeniedException("Test source authorization changed");
                return snapshot();
            }

            public ControlConfiguration configurationInCurrentTransaction(ExecutionContext context) {
                return configuration(context);
            }

            public ControlConfiguration definitions() {
                return snapshot();
            }

            public ControlConfiguration.Grant grant(ExecutionContext context) {
                configuration(context);
                return grants.stream().filter(g -> g.subjectName().equals(context.authorization().principal().subjectName())
                        && g.tenantId().equals(context.authorization().tenantId()) && g.workspaceId().equals(context.authorization().workspaceId())).findFirst().orElseThrow();
            }
        };
    }
}
