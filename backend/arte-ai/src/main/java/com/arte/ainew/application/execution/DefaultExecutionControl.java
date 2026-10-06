package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.execution.ControlReceipt;
import com.arte.ainew.pojo.execution.ExecutionControlRequest;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

/**
 * 当前权限下读取 Invocation 权威；控制委托协调器，未启用的控制明确拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class DefaultExecutionControl implements ExecutionControl {

    private final AdmissionAuthorization admissionAuthorization;
    private final ExecutionStore executionStore;
    private final InvocationCoordinator invocationCoordinator;

    public DefaultExecutionControl(AdmissionAuthorization admissionAuthorization, ExecutionStore executionStore, InvocationCoordinator invocationCoordinator) {
        this.admissionAuthorization = admissionAuthorization;
        this.executionStore = executionStore;
        this.invocationCoordinator = invocationCoordinator;
    }

    @Override
    public Mono<Invocation> status(String invocationId, ExecutionContext context) {
        return admissionAuthorization.require(context, AdmissionAuthorization.READ)
                .flatMap(current -> executionStore.find(ExecutionOwner.from(current), invocationId))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    @Override
    public Mono<ControlReceipt> request(ExecutionControlRequest request, ExecutionContext context) {
        return invocationCoordinator.control(request, context);
    }
}
