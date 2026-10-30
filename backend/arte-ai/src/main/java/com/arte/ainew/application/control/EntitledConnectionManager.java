package com.arte.ainew.application.control;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 连接使用入口
 * <p>
 * 准备阶段解析资格，真正模型外发前重新进入本次请求的授权门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 16:50 ✾
 */
public final class EntitledConnectionManager implements ConnectionManager {

    private final AiResourceEntitlementService entitlements;
    private final AdmissionAuthorization authorization;
    private final AiAdmissionAuthorizationGuard guard;

    public EntitledConnectionManager(AiResourceEntitlementService entitlements, AdmissionAuthorization authorization, AiAdmissionAuthorizationGuard guard) {
        this.entitlements = Objects.requireNonNull(entitlements);
        this.authorization = Objects.requireNonNull(authorization);
        this.guard = Objects.requireNonNull(guard);
    }

    @Override
    public Mono<ConnectionDefinition> resolve(DefinitionRef reference, ExecutionContext context) {
        return entitlements.resolveConnection(reference, context);
    }

    @Override
    public Mono<ConnectionDefinition> resolveCurrent(DefinitionRef reference, ExecutionContext context) {
        return authorization.revalidate(context, current -> resolve(reference, current));
    }

    @Override
    public Mono<ConnectionDefinition> resolveCurrent(DefinitionRef reference, GatewayCall<?> call) {
        if (!call.binding().connection().equals(reference))
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        return Mono.deferContextual(values -> guard.authorize(new AiAdmissionAuthorizationGuard.Check(call.request(), call.binding(), null,
                        values.getOrDefault(ToolSetSnapshot.class, null), AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND))
                .map(AiAdmissionAuthorizationGuard.Permit::connection));
    }
}
