package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.persistence.mybatis.mapper.ToolChatRunMapper;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索源、搜索回查共用的最终模型归属校验；中间模型／工具调用没有聊天投影。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
final class ToolChatProjection {

    private ToolChatProjection() {
    }

    static ToolChatRunMapper.Chat finalChat(ToolChatRunMapper mapper, ExecutionRecordCodec codec, Invocation child) {
        var parent = child.request().context().parentExecutionId();
        if (parent == null || child.conversation() != null) return null;
        var row = mapper.find(CanonicalJson.key(parent), false);
        return finalChat(row, mapper.chat(CanonicalJson.key(parent)), codec, child);
    }

    /**
     * 一页多个助手消息共享根读取，最多两次 SQL，避免逐命中查 Run／关联。
     */
    static Map<String, ToolChatRunMapper.Chat> finalChats(ToolChatRunMapper mapper, ExecutionRecordCodec codec, Collection<Invocation> children) {
        var ids = children.stream().filter(i -> i.conversation() == null && i.request().context().parentExecutionId() != null)
                .map(i -> CanonicalJson.key(i.request().context().parentExecutionId())).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var rows = mapper.findMany(ids).stream().collect(Collectors.toMap(ToolChatRunMapper.Row::runKey, r -> r));
        var chats = mapper.chats(ids).stream().collect(Collectors.toMap(ToolChatRunMapper.Chat::runKey, r -> r));
        var result = new HashMap<String, ToolChatRunMapper.Chat>();
        for (var child : children) {
            var parent = child.request().context().parentExecutionId();
            if (child.conversation() != null || parent == null) continue;
            var chat = finalChat(rows.get(CanonicalJson.key(parent)), chats.get(CanonicalJson.key(parent)), codec, child);
            if (chat != null) result.put(child.request().context().executionId(), chat);
        }
        return Map.copyOf(result);
    }

    private static ToolChatRunMapper.Chat finalChat(ToolChatRunMapper.Row row, ToolChatRunMapper.Chat chat, ExecutionRecordCodec codec, Invocation child) {
        if (row == null) return null;
        var parent = child.request().context().parentExecutionId();
        if (!CanonicalJson.sha256(row.stateSnapshot()).equals(row.stateDigest()))
            throw new IllegalStateException("Run state checksum mismatch");
        var run = codec.decode(row.stateSnapshot(), ToolChatRun.class);
        if (!row.runKey().equals(CanonicalJson.key(parent)) || !run.runId().equals(parent) || run.version() != row.version() || !run.owner().equals(ExecutionOwners.from(child.request().context()))
                || !row.ownerKey().equals(CanonicalJson.key(run.owner().tenantId(), run.owner().workspaceId(), run.owner().subjectId())))
            throw new IllegalStateException("Run projection identity mismatch");
        if (chat == null || run.state() != ToolChatRun.State.SUCCEEDED || run.result() == null || run.result().partial()
                || child.state() != Invocation.State.SUCCEEDED || !run.result().equals(child.result())
                || !child.request().context().executionId().equals(chat.finalInvocationId())) return null;
        return chat;
    }

    static AnswerCandidate candidate(Invocation child) {
        return child.conversation() != null || child.request().context().parentExecutionId() == null ? AnswerCandidate.invocation(child.request().context().executionId())
                : AnswerCandidate.run(child.request().context().parentExecutionId());
    }
}
