package com.arte.ai.model.message;

import com.arte.base.validation.ContractChecks;

/**
 * 文本快照；允许空文本片段，整体请求由网关校验。
 */
public record TextPart(String text) implements ContentPart {
    public TextPart {
        text = ContractChecks.required(text, "text");
    }
}
