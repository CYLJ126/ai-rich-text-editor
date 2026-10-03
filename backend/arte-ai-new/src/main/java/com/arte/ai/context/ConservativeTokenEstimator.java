package com.arte.ai.context;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.message.Message;
import com.arte.ai.spi.strategy.TokenEstimator;

import java.util.List;

/**
 * UTF-8 字节加每条消息及回复模板余量；明确为保守估算，不冒充 DeepSeek 官方分词器。
 */
public final class ConservativeTokenEstimator implements TokenEstimator {
    public int estimate(List<Message> messages) {
        return Math.addExact(ChatValues.bytes(messages), Math.addExact(Math.multiplyExact(messages.size(), 64), 64));
    }

    public String version() {
        return "utf8-byte-upper-bound-v1";
    }
}
