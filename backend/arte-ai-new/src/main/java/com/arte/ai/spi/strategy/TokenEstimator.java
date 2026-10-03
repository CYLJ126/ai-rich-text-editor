package com.arte.ai.spi.strategy;

import com.arte.ai.model.message.Message;

import java.util.List;

/**
 * 计量策略可由模型专用分词器替换；估算值不作为供应商账单用量。
 */
public interface TokenEstimator {
    int estimate(List<Message> messages);

    String version();
}
