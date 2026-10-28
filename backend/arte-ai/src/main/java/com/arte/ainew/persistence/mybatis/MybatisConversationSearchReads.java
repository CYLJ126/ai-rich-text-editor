package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.persistence.mybatis.mapper.PayloadMapper;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows;
import com.arte.ainew.persistence.mybatis.mapper.SearchReadMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolChatRunMapper;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchReads;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 基于 MyBatis 回查搜索命中及模型调用的当前可见性，并读取消息定位所需的聊天历史窗口。
 * 结合归档资格过滤结果，在只读快照事务中校验会话及版本，不增加业务写入步骤。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class MybatisConversationSearchReads implements ConversationSearchReads {

    private final SearchReadMapper mapper;
    private final PayloadMapper payloads;
    private final ToolChatRunMapper runs;
    private final ExecutionRecordCodec codec;
    private final ConversationArchiveReader archives;
    private final TransactionTemplate read;

    private record Current(Conversation conversation, String title) {
    }

    public MybatisConversationSearchReads(DataSource dataSource, ExecutionRecordCodec codec, ConversationArchiveReader archives) {
        var sessions = new SqlSessionTemplate(SearchSqlSessionFactory.create(dataSource));
        mapper = sessions.getMapper(SearchReadMapper.class);
        payloads = sessions.getMapper(PayloadMapper.class);
        runs = sessions.getMapper(ToolChatRunMapper.class);
        this.codec = codec;
        this.archives = archives;
        read = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(5);
    }

    private String ownerKey(ExecutionOwner o) {
        return key(o.tenantId(), o.workspaceId(), o.subjectId());
    }

    private Map<String, Current> conversations(ExecutionOwner owner, Collection<String> ids) {
        var result = new HashMap<String, Current>();
        if (ids.isEmpty()) return result;
        for (var row : mapper.conversations(ownerKey(owner), ids.stream().map(CanonicalJson::key).toList())) {
            var c = codec.decode(row.snapshot(), Conversation.class);
            if (!row.idKey().equals(key(c.conversationId())) || !owner.equals(c.owner()))
                throw new IllegalStateException("Conversation scope mismatch");
            if (c.state() == Conversation.State.ACTIVE && !Boolean.FALSE.equals(row.active()))
                result.put(c.conversationId(), new Current(c, row.title() == null ? c.title() : row.title()));
        }
        return result;
    }

    private Set<String> archived(ExecutionOwner owner, Set<String> ids) {
        if (ids.isEmpty()) return Set.of();
        var result = archives.findArchivedConversationIds(owner, Set.copyOf(ids)).block(Duration.ofSeconds(2));
        if (result == null) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
        return result;
    }

    private Map<String, Turn> turns(Collection<String> conversations, Collection<String> ids) {
        var result = new HashMap<String, Turn>();
        if (ids.isEmpty() || conversations.isEmpty()) return result;
        for (var bytes : mapper.turns(conversations.stream().map(CanonicalJson::key).toList(), ids.stream().map(CanonicalJson::key).toList())) {
            var t = codec.decode(bytes, Turn.class);
            result.put(t.turnId(), t);
        }
        return result;
    }

    private Map<String, Invocation> invocations(ExecutionOwner owner, Collection<String> ids) {
        var result = new HashMap<String, Invocation>();
        if (ids.isEmpty()) return result;
        for (var bytes : mapper.invocations(ownerKey(owner), ids.stream().map(CanonicalJson::key).toList())) {
            var i = codec.decode(bytes, Invocation.class);
            if (!owner.equals(ExecutionOwners.from(i.request().context())))
                throw new IllegalStateException("Invocation scope mismatch");
            result.put(i.request().context().executionId(), i);
        }
        return result;
    }

    private static String string(Map<String, Object> source, String name) {
        return source.get(name) instanceof String value ? value : null;
    }

    private static String text(ChatMessage message) {
        return message.content().stream().filter(ChatMessage.Text.class::isInstance).map(ChatMessage.Text.class::cast).map(ChatMessage.Text::text).collect(Collectors.joining("\n"));
    }

    @Override
    public Map<String, Visible> visible(ExecutionOwner owner, List<ConversationSearchIndex.Hit> hits) {
        if (hits.size() > 100) throw new IllegalArgumentException("Visibility batch exceeds limit");
        if (hits.isEmpty()) return Map.of();
        var candidates = hits.stream().filter(h -> owner.tenantId().equals(h.source().get("tenantId")) && owner.workspaceId().equals(h.source().get("workspaceId"))
                && owner.subjectId().equals(h.source().get("subjectId")) && Boolean.TRUE.equals(h.source().get("searchable"))).toList();
        var ids = candidates.stream().map(h -> string(h.source(), "conversationId")).filter(Objects::nonNull).collect(Collectors.toSet());
        var excluded = archived(owner, ids);
        return read.execute(status -> {
            var conversations = conversations(owner, ids);
            excluded.forEach(conversations::remove);
            var turns = turns(conversations.keySet(), candidates.stream().map(h -> string(h.source(), "turnId")).filter(Objects::nonNull).collect(Collectors.toSet()));
            var invocations = invocations(owner, candidates.stream().map(h -> string(h.source(), "invocationId")).filter(Objects::nonNull).collect(Collectors.toSet()));
            var runLinks = ToolChatProjection.finalChats(runs, codec, invocations.values());
            var resultIds = invocations.values().stream().filter(i -> i.result() != null && i.state().terminal()).map(i -> i.result().resultId()).distinct().toList();
            var results = new HashMap<String, PersistenceRows.ResultBatchRow>();
            if (!resultIds.isEmpty())
                for (var r : payloads.results(ownerKey(owner), resultIds)) results.put(r.idKey(), r);
            var decoded = new HashMap<String, InvocationResult.Generation>();
            var output = new LinkedHashMap<String, Visible>();
            for (var hit : candidates) {
                var s = hit.source();
                String convId = string(s, "conversationId");
                var c = conversations.get(convId);
                if (c == null) continue;
                if ("TITLE".equals(s.get("recordKind"))) {
                    if (hit.entryKey().equals(SearchIndexRecord.key(owner, convId, "TITLE")))
                        output.put(hit.entryKey(), new Visible(c.title(), null, null, null, null, false, c.title()));
                    continue;
                }
                if (!"MESSAGE".equals(s.get("recordKind"))) continue;
                String turnId = string(s, "turnId"), messageId = string(s, "messageId"), invocationId = string(s, "invocationId");
                var turn = turns.get(turnId);
                if (turn == null || !turn.conversationId().equals(convId) || messageId == null) continue;
                if (!hit.entryKey().equals(SearchIndexRecord.key(owner, convId, "MESSAGE", turnId, invocationId == null ? "" : invocationId, messageId)))
                    continue;
                if (invocationId == null) {
                    if ("USER".equals(s.get("role")) && turn.userMessage().messageId().equals(messageId) && !text(turn.userMessage()).isBlank())
                        output.put(hit.entryKey(), new Visible(c.title(), turnId, messageId, null, null, false, text(turn.userMessage())));
                    continue;
                }
                var invocation = invocations.get(invocationId);
                int ordinal = invocation == null ? -1 : turn.candidates().indexOf(ToolChatProjection.candidate(invocation));
                if (!"ASSISTANT".equals(s.get("role")) || ordinal < 0 || invocation == null || !linked(invocation, convId, turnId, runLinks.get(invocationId)) || !invocation.state().terminal() || invocation.result() == null)
                    continue;
                var row = results.get(invocation.result().resultId());
                if (row == null) continue;
                var generation = decoded.computeIfAbsent(invocationId, id -> generation(owner, invocation, row));
                if (generation == null) continue;
                generation.value().outputs().stream().filter(m -> m.messageId().equals(messageId) && !text(m).isBlank()).findFirst().ifPresent(m ->
                        output.put(hit.entryKey(), new Visible(c.title(), turnId, messageId, invocationId, ordinal + 1, row.partial() == 1, text(m), ToolChatProjection.candidate(invocation))));
            }
            return Map.copyOf(output);
        });
    }

    private InvocationResult.Generation generation(ExecutionOwner owner, Invocation i, PersistenceRows.ResultBatchRow row) {
        var ref = i.result();
        if (!"model-result".equals(ref.resultType()) || i.request().kind() != com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION)
            return null;
        if (!row.ownerKey().equals(ownerKey(owner)) || !row.invocationKey().equals(key(i.request().context().executionId()))
                || i.activeAttemptId() == null || !row.attemptKey().equals(key(i.activeAttemptId())) || row.schemaVersion() != 1 || row.partial() < 0 || row.partial() > 1
                || !ref.equals(new ResultRef(row.idKey(), row.resultType(), row.schemaVersion(), row.payloadDigest(), row.partial() == 1))
                || !CanonicalJson.sha256(row.workspaceSnapshot()).equals(row.payloadDigest()))
            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
        var result = codec.decode(row.workspaceSnapshot(), InvocationResult.class);
        if (!(result instanceof InvocationResult.Generation g) || g.value().complete() == ref.partial())
            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
        return g;
    }

    private static boolean linked(Invocation i, String conv, String turn, ToolChatRunMapper.Chat chat) {
        if (i.conversation() != null)
            return i.conversation().conversationId().equals(conv) && i.conversation().turnId().equals(turn);
        return chat != null && chat.conversationId().equals(conv) && chat.turnId().equals(turn);
    }

    @Override
    public Set<String> readableInvocations(ExecutionOwner owner, Map<String, Invocation> invocations) {
        if (invocations.size() > 20) throw new IllegalArgumentException("Invocation visibility batch exceeds limit");
        // 先取得父 Run 归属以批量校验归档；事务内再次核验最终子调用及 Turn，避免孤儿 ID 绕过会话可见性。
        var links = ToolChatProjection.finalChats(runs, codec, invocations.values());
        var ids = new HashSet<String>();
        invocations.values().stream().filter(i -> i.conversation() != null).forEach(i -> ids.add(i.conversation().conversationId()));
        links.values().forEach(c -> ids.add(c.conversationId()));
        var excluded = archived(owner, ids);
        return read.execute(status -> {
            var currentLinks = ToolChatProjection.finalChats(runs, codec, invocations.values());
            var cs = conversations(owner, ids);
            excluded.forEach(cs::remove);
            var turnIds = new HashSet<String>();
            invocations.values().stream().filter(i -> i.conversation() != null).forEach(i -> turnIds.add(i.conversation().turnId()));
            links.values().forEach(c -> turnIds.add(c.turnId()));
            var ts = turns(cs.keySet(), turnIds);
            return invocations.entrySet().stream().filter(e -> {
                var i = e.getValue();
                if (!ExecutionOwners.from(i.request().context()).equals(owner)) return false;
                if (i.conversation() == null && i.request().context().parentExecutionId() == null) return true;
                var chat = currentLinks.get(e.getKey());
                String conv = i.conversation() != null ? i.conversation().conversationId() : chat == null ? null : chat.conversationId();
                String turn = i.conversation() != null ? i.conversation().turnId() : chat == null ? null : chat.turnId();
                var t = ts.get(turn);
                return cs.containsKey(conv) && t != null && linked(i, conv, turn, chat) && t.candidates().contains(ToolChatProjection.candidate(i));
            }).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
        });
    }

    @Override
    public void requireConversation(ExecutionOwner owner, String id, long version) {
        if (archived(owner, Set.of(id)).contains(id))
            throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
        read.executeWithoutResult(status -> {
            var c = conversations(owner, Set.of(id)).get(id);
            if (c == null) throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
            if (c.conversation().version() != version) throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
        });
    }

    @Override
    public Window around(ExecutionOwner owner, Around request) {
        if (request.size() < 1 || request.size() > 10)
            throw new IllegalArgumentException("History window exceeds limit");
        if (archived(owner, Set.of(request.conversationId())).contains(request.conversationId()))
            throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
        return read.execute(status -> {
            var c = conversations(owner, Set.of(request.conversationId())).get(request.conversationId());
            if (c == null) throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND);
            if (c.conversation().version() != request.version())
                throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
            String convKey = key(request.conversationId());
            var rows = new ArrayList<Turn>();
            if (request.afterSequence() != null) {
                var anchor = mapper.target(convKey, key(request.turnId()));
                if (anchor == null) throw new AdmissionException(ResultCodeEnum.AI_TURN_NOT_FOUND);
                var target = codec.decode(anchor, Turn.class);
                if (!target.turnId().equals(request.turnId()) || target.sequence() != request.afterSequence())
                    throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
                rows.addAll(range(convKey, request.afterSequence(), request.backward(), request.size()));
            } else {
                var bytes = mapper.target(convKey, key(request.turnId()));
                if (bytes == null) throw new AdmissionException(ResultCodeEnum.AI_TURN_NOT_FOUND);
                var target = codec.decode(bytes, Turn.class);
                if (!target.turnId().equals(request.turnId()) || !target.conversationId().equals(request.conversationId()))
                    throw new IllegalStateException("Turn identity mismatch");
                var earlier = range(convKey, target.sequence(), true, request.size() - 1);
                var later = range(convKey, target.sequence(), false, request.size() - 1);
                int before = Math.min(earlier.size(), (request.size() - 1) / 2), after = Math.min(later.size(), request.size() - 1 - before);
                before = Math.min(earlier.size(), request.size() - 1 - after);
                var leading = new ArrayList<>(earlier.subList(0, before));
                Collections.reverse(leading);
                rows.addAll(leading);
                rows.add(target);
                rows.addAll(later.subList(0, after));
            }
            if (request.afterSequence() != null && request.backward()) Collections.reverse(rows);
            if (rows.stream().anyMatch(t -> !t.conversationId().equals(request.conversationId())))
                throw new IllegalStateException("History scope mismatch");
            long first = rows.isEmpty() ? request.afterSequence() : rows.getFirst().sequence(), last = rows.isEmpty() ? request.afterSequence() : rows.getLast().sequence();
            return new Window(request.version(), List.copyOf(rows), mapper.exists(convKey, first, true) > 0, mapper.exists(convKey, last, false) > 0);
        });
    }

    private List<Turn> range(String conversation, long after, boolean backward, int size) {
        if (size == 0) return List.of();
        return mapper.range(conversation, after, backward, size).stream().map(bytes -> codec.decode(bytes, Turn.class)).toList();
    }
}
