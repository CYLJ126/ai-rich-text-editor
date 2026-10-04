package com.arte.ainew.pojo.remote;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;

import java.io.Serializable;
import java.util.Objects;

/**
 * 远端调用请求
 * 远程应用输入与继续交流语义；地址和凭据从绑定解析，远端会话必须匹配当前主体与实际连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:22 ✾
 */
public record RemoteApplicationRequest(Operation operation, StructuredValue.ObjectValue input,
                                       SessionRef session) implements CapabilityInput {
    public enum Operation {START, CONTINUE}

    public record SessionRef(String sessionId, DefinitionRef connection, ExecutionOwner owner) implements Serializable {
        public SessionRef {
            ContractChecks.id(sessionId, "sessionId");
            Objects.requireNonNull(connection, "connection").requireType("connection");
            Objects.requireNonNull(owner, "owner");
        }
    }

    public RemoteApplicationRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(input, "input");
        ContractChecks.require((operation == Operation.CONTINUE) == (session != null), "Only continue requires an existing session");
    }

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.REMOTE_APPLICATION;
    }
}
