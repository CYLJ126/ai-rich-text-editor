package com.arte.app.platform.ai;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.LocalToolCall;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.app.platform.ai.tool.LocalToolPolicy;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.util.HashSet;

/**
 * 受理与实际执行的宿主授权：复用现有当前平台资格，再按完整受信契约核验免费策略与领域资源资格。
 * 不按 READ_ONLY 或空预算推断免费，不接受任意同名工具；提交及重放在原物理事务复核。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
public final class PlatformToolExecutionAccess implements ToolAdmissionAccess, ToolExecutionAccess {
    private final PlatformToolManagementAccess platform;
    private final LocalToolPolicy tools;

    public PlatformToolExecutionAccess(PlatformToolManagementAccess platform, LocalToolPolicy tools) {
        this.platform = platform;
        this.tools = tools;
    }

    @Override
    public Mono<Permit> authorizeFreeReadOnly(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        if (binding.tool().tool().risk().sideEffect() != com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY)
            return Mono.error(denied());
        return authorizeReviewed(input, context, binding);
    }

    private Mono<Permit> authorizeReviewed(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.defer(() -> {
            verify(input, context, binding);
            return platform.authorizeUse(context).map(permit -> {
                tools.requireResource(input, context, binding);
                var scopes = new HashSet<>(context.authorization().scopes());
                scopes.retainAll(permit.executionScopes());
                if (!scopes.containsAll(binding.tool().tool().risk().requiredScopes())) throw denied();
                var authorization = context.authorization().restrictTo(scopes);
                return new Permit() {
                    public ExecutionAuthorization authorization() {
                        return authorization;
                    }

                    public void requireInCurrentTransaction(ToolExecutionSnapshot actual) {
                        verify(actual.input(), context, actual.binding());
                        permit.requireInCurrentTransaction(actual.binding().tool().tool().risk().requiredScopes());
                        tools.requireResource(actual.input(), context, actual.binding());
                    }
                };
            });
        });
    }

    @Override
    public Mono<Permit> authorizeFreeWrite(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        if (binding.tool().tool().risk().sideEffect() != com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.RESOURCE_WRITE)
            return Mono.error(denied());
        return authorizeReviewed(input, context, binding);
    }

    @Override
    public Mono<ExecutionAuthorization> authorize(LocalToolCall call) {
        var request = call.call().request();
        var input = new ToolAdmissionInput(call.binding().definition().id(), request.input().arguments(),
                request.options().requestedTimeout(), request.options().maxAttempts(), request.options().maxOutputBytes());
        return authorizeFree(input, call.call().runtime().execution(), call.binding()).map(Permit::authorization);
    }

    private void verify(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        if (context.budgetRef() != null || context.parentExecutionId() != null || !binding.owner().equals(ExecutionOwner.from(context)))
            throw denied();
        tools.requireFree(input, binding);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current local tool execution access denied");
    }
}
