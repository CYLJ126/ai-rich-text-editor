package com.arte.base.model.security;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

/**
 * 针对一个资源的一项动作请求。context 记录发起者作用域，executor 记录实际执行主体。
 * 接入层须验证两者；不同于发起者的 executor 只接受服务主体，且不能扩大原任务权限。
 * actionCode 保存动作码快照，避免把可变的扩展对象带入判定身份。
 */
public record AuthorizationRequest(
        ExecutionContext context,
        PrincipalRef executor,
        ResourceRef resource,
        String actionCode
) {

    public AuthorizationRequest {
        context = ContractChecks.required(context, "context");
        executor = ContractChecks.required(executor, "executor");
        resource = ContractChecks.required(resource, "resource");
        actionCode = ContractChecks.identifier(actionCode, "actionCode");
        if (!executor.equals(context.scope().principal()) && executor.type() != PrincipalType.SERVICE) {
            throw new IllegalArgumentException("delegated executor must be a service principal");
        }
    }

    public static AuthorizationRequest of(ExecutionContext context, ResourceRef resource, ResourceAction action) {
        ContractChecks.required(context, "context");
        ContractChecks.required(action, "action");
        return new AuthorizationRequest(context, context.scope().principal(), resource, action.code());
    }
}
