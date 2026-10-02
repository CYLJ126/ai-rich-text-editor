package com.arte.base.model.security;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.validation.ContractChecks;

import java.util.List;

/**
 * 资料外发的完整判定范围，不包含正文或凭据。
 * sources 为实际发送的来源副本；仅发送用户消息时可为空，但不能为 null。
 * contentDigest 覆盖完整实际发送内容；来源、用途、目的地或内容改变时必须重新判定。
 * consentRef 可为空，由提供者核对明确授权或用户设置；存在引用本身不证明用户已同意。
 */
public record EgressRequest(
        ExecutionContext context,
        PrincipalRef executor,
        List<SourceRef> sources,
        EgressDestination destination,
        String purpose,
        String contentDigest,
        ResourceRef consentRef
) {

    public EgressRequest {
        context = ContractChecks.required(context, "context");
        executor = ContractChecks.required(executor, "executor");
        ContractChecks.required(sources, "sources");
        sources.forEach(source -> ContractChecks.required(source, "sources element"));
        sources = List.copyOf(sources);
        destination = ContractChecks.required(destination, "destination");
        purpose = ContractChecks.identifier(purpose, "purpose");
        contentDigest = ContractChecks.identifier(contentDigest, "contentDigest");
        if (!executor.equals(context.scope().principal()) && executor.type() != PrincipalType.SERVICE) {
            throw new IllegalArgumentException("delegated executor must be a service principal");
        }
    }

    public static EgressRequest of(ExecutionContext context, List<SourceRef> sources,
                                   EgressDestination destination, String purpose, String contentDigest,
                                   ResourceRef consentRef) {
        ContractChecks.required(context, "context");
        return new EgressRequest(context, context.scope().principal(), sources, destination, purpose, contentDigest, consentRef);
    }
}
