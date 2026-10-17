package com.arte.ainew.pojo.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchText;
import com.arte.ainew.common.validation.ContractChecks;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * 一条会话标题或消息的 ES 检索投影，携带归属、定位标识、可搜索文本及同步版本。
 * 可由权威数据重建，仅保存可见文本；非可搜索记录清除正文及内容摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public record SearchIndexRecord(String entryKey, String conversationKey, ExecutionOwner owner,
                                String conversationId, ConversationSearch.Kind recordKind, String role,
                                String turnId, String messageId, String invocationId, Long turnSequence,
                                Integer candidateOrdinal, List<String> groupIds, String title, String body,
                                Instant generatedAt, boolean partial, boolean searchable,
                                long sourceRevision, String extractorVersion, String contentHash, String sourceRef) {

    /**
     * 校验标题或消息的身份及字段约束，计算内容摘要，并确保墓碑记录不携带正文。
     */
    public SearchIndexRecord {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(conversationId, "conversationId");
        Objects.requireNonNull(recordKind, "recordKind");
        groupIds = SearchText.groups(groupIds);
        ContractChecks.range(sourceRevision, "sourceRevision", 1, Long.MAX_VALUE);
        ContractChecks.id(extractorVersion, "extractorVersion");
        ContractChecks.optionalId(sourceRef, "sourceRef");
        ContractChecks.require(key(owner, conversationId).equals(conversationKey), "Invalid conversation key");
        if (recordKind == ConversationSearch.Kind.TITLE) {
            ContractChecks.require(role == null && turnId == null && messageId == null && invocationId == null
                    && turnSequence == null && candidateOrdinal == null && !partial && body == null, "Invalid title record");
            if (searchable)
                ContractChecks.text(title, "title", ContractChecks.MAX_TEXT_CHARS + ContractChecks.MAX_PARTS + 2);
            ContractChecks.require(key(owner, conversationId, "TITLE").equals(entryKey), "Invalid entry key");
        } else {
            ContractChecks.id(turnId, "turnId");
            ContractChecks.id(messageId, "messageId");
            ContractChecks.require("USER".equals(role) || "ASSISTANT".equals(role), "Invalid message role");
            ContractChecks.require(turnSequence != null && turnSequence > 0 && title == null, "Invalid message metadata");
            if ("ASSISTANT".equals(role)) {
                ContractChecks.id(invocationId, "invocationId");
                ContractChecks.require(candidateOrdinal != null && candidateOrdinal > 0, "Candidate ordinal required");
            } else
                ContractChecks.require(invocationId == null && candidateOrdinal == null && !partial, "Invalid user message");
            if (searchable) ContractChecks.text(body, "body", ContractChecks.MAX_TEXT_CHARS + ContractChecks.MAX_PARTS);
            ContractChecks.require(key(owner, conversationId, "MESSAGE", turnId, invocationId == null ? "" : invocationId, messageId)
                    .equals(entryKey), "Invalid entry key");
        }
        if (!searchable) {
            ContractChecks.require(title == null && body == null && contentHash == null, "Tombstone must clear text");
        } else {
            String actualHash = textHash(recordKind == ConversationSearch.Kind.TITLE ? title : body);
            if (contentHash != null) ContractChecks.require(actualHash.equals(contentHash), "Content hash mismatch");
            contentHash = actualHash;
        }
    }

    /**
     * 为会话标题创建可搜索记录，并生成稳定的会话键和记录键。
     */
    public static SearchIndexRecord title(ExecutionOwner owner, String id, String title, Instant at, long revision) {
        return new SearchIndexRecord(key(owner, id, "TITLE"), key(owner, id), owner, id, ConversationSearch.Kind.TITLE,
                null, null, null, null, null, null, List.of(), title, null, at, false, true, revision, "v1", null, null);
    }

    /**
     * 创建用户问题或助手候选回答的索引记录，保留精确定位及部分回答信息。
     */
    public static SearchIndexRecord message(ExecutionOwner owner, String id, String turnId, String messageId,
                                            String invocationId, int ordinal, long sequence, String body, Instant at, boolean partial, long revision) {
        return new SearchIndexRecord(key(owner, id, "MESSAGE", turnId, invocationId == null ? "" : invocationId, messageId),
                key(owner, id), owner, id, ConversationSearch.Kind.MESSAGE, invocationId == null ? "USER" : "ASSISTANT",
                turnId, messageId, invocationId, sequence, invocationId == null ? null : ordinal, List.of(), null, body, at,
                partial, true, revision, "v1", null, null);
    }

    /**
     * 返回附带来源引用的新记录，其余索引内容保持不变。
     */
    public SearchIndexRecord withSourceRef(String reference) {
        return new SearchIndexRecord(entryKey, conversationKey, owner, conversationId, recordKind, role, turnId, messageId, invocationId, turnSequence, candidateOrdinal,
                groupIds, title, body, generatedAt, partial, searchable, sourceRevision, extractorVersion, contentHash, reference);
    }

    /**
     * 转换为 ES 文档字段，将主体归属展开为租户、空间及主体标识。
     */
    public Map<String, Object> source() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entryKey", entryKey);
        m.put("conversationKey", conversationKey);
        m.put("tenantId", owner.tenantId());
        m.put("workspaceId", owner.workspaceId());
        m.put("subjectId", owner.subjectId());
        m.put("conversationId", conversationId);
        m.put("recordKind", recordKind.name());
        m.put("role", role);
        m.put("turnId", turnId);
        m.put("messageId", messageId);
        m.put("invocationId", invocationId);
        m.put("turnSequence", turnSequence);
        m.put("candidateOrdinal", candidateOrdinal);
        m.put("groupIds", groupIds);
        m.put("title", title);
        m.put("body", body);
        m.put("generatedAt", generatedAt == null ? null : generatedAt.toString());
        m.put("partial", partial);
        m.put("searchable", searchable);
        m.put("sourceRevision", sourceRevision);
        m.put("extractorVersion", extractorVersion);
        m.put("contentHash", contentHash);
        m.put("sourceRef", sourceRef);
        return Collections.unmodifiableMap(m);
    }

    /**
     * 计算文本的 SHA-256 摘要，用于核验索引内容。
     */
    private static String textHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 按主体归属和带长度前缀的业务标识生成稳定键，避免不同标识组合发生拼接歧义。
     */
    public static String key(ExecutionOwner owner, String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var values = new ArrayList<>(List.of("conversation-search-v1", owner.tenantId(), owner.workspaceId(), owner.subjectId()));
            values.addAll(List.of(parts));
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
