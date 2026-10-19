package com.arte.ainew.web.response;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.web.ToolManagementValues;

/**
 * 服务层按权威引用校验后的结果；kind 区分封闭结果类型，生成结果保留 complete 和未知用量。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public record InvocationResultResponse(String invocationId, CapabilityDescriptor.Kind kind, InvocationResult result,
                                       String outputJson) {

    public InvocationResultResponse(String invocationId, CapabilityDescriptor.Kind kind, InvocationResult result) {
        this(invocationId, kind, result, result instanceof InvocationResult.Tool tool && tool.value().output() != null
                ? CanonicalJson.encode(ToolManagementValues.json(tool.value().output())) : null);
    }

    public static InvocationResultResponse from(String invocationId, InvocationResult result) {
        var kind = switch (result) {
            case InvocationResult.Generation ignored -> CapabilityDescriptor.Kind.GENERATION;
            case InvocationResult.Embedding ignored -> CapabilityDescriptor.Kind.EMBEDDING;
            case InvocationResult.Tool ignored -> CapabilityDescriptor.Kind.TOOL;
            case InvocationResult.Media ignored -> CapabilityDescriptor.Kind.MEDIA;
            case InvocationResult.RemoteApplication ignored -> CapabilityDescriptor.Kind.REMOTE_APPLICATION;
        };
        return new InvocationResultResponse(invocationId, kind, result);
    }
}
