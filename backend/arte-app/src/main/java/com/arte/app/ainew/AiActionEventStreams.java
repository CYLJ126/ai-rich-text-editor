package com.arte.app.ainew;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;

/**
 * 独立动作复用聊天的有界 SSE 发送及事件补读机制，无需启用聊天或创建会话。
 */
public final class AiActionEventStreams implements AutoCloseable {
    private final NewAiActionCallService service;
    private final ChatEventStreams streams;

    public AiActionEventStreams(NewAiActionCallService service, int capacity, Duration timeout, JdbcModelExecutionStore ledger) {
        this.service = service;
        this.streams = new ChatEventStreams(null, capacity, timeout, ledger);
    }

    public SseEmitter open(NewAiActionCallService.Observation observation, long after) {
        return streams.open(observation.executionId(), after, (cursor, limit) -> service.events(observation, cursor, limit));
    }

    @Override
    public void close() {
        streams.close();
    }
}
