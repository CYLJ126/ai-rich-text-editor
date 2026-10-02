package com.arte.ai.model.message;

import com.arte.base.validation.ContractChecks;
import java.util.List;

/**
 * 不依赖供应商 SDK 的不可变消息快照。
 */
public record Message(MessageRole role, List<ContentPart> parts) {
    public Message {
        role = ContractChecks.required(role, "role");
        parts = List.copyOf(ContractChecks.required(parts, "parts"));
        if (parts.isEmpty()) throw new IllegalArgumentException("parts must not be empty");
    }
}
