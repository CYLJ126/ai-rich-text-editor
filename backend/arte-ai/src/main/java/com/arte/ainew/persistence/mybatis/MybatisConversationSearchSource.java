package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.mybatis.mapper.PayloadMapper;
import com.arte.ainew.persistence.mybatis.mapper.SearchSourceMapper;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.ResultRef;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.search.ConversationSearchSource;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 会话搜索后台同步的数据抽取实现，通过 MyBatis 扫描会话、轮次和模型调用，生成标题及消息索引记录。
 * 结合当前归属和归档资格过滤内容，支持历史候选及已持久化的部分回答，不读取流式预览或工具正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class MybatisConversationSearchSource implements ConversationSearchSource {

    private final SearchSourceMapper source;
    private final PayloadMapper payloads;
    private final ExecutionRecordCodec codec;
    private final ConversationArchiveReader archives;

    public MybatisConversationSearchSource(DataSource dataSource, ExecutionRecordCodec codec, ConversationArchiveReader archives) {
        var sessions = new SqlSessionTemplate(SearchSqlSessionFactory.create(dataSource));
        source = sessions.getMapper(SearchSourceMapper.class);
        payloads = sessions.getMapper(PayloadMapper.class);
        this.codec = Objects.requireNonNull(codec);
        this.archives = Objects.requireNonNull(archives);
    }

    @Override
    public List<Unit> scan(Kind kind, String after, int size) {
        if (size < 1 || size > 100) throw new IllegalArgumentException("Invalid source batch size");
        return source.keys(kind.name(), after, size).stream().map(k -> new Unit(kind, k)).toList();
    }

    private <T> T read(Kind kind, String key, Class<T> type) {
        var bytes = source.snapshot(kind.name(), key);
        return bytes == null ? null : codec.decode(bytes, type);
    }

    @Override
    public Snapshot read(Unit unit, Scope previousScope) {
        return batch(List.of(new Work(unit, previousScope, false, 0))).read(unit, previousScope);
    }

    private Scope resolveScope(Work work) {
        try {
            String conversationId = switch (work.unit().kind()) {
                case C -> {
                    var c = read(Kind.C, work.unit().key(), Conversation.class);
                    yield c == null ? null : c.conversationId();
                }
                case T -> {
                    var t = read(Kind.T, work.unit().key(), Turn.class);
                    yield t == null ? null : t.conversationId();
                }
                case I -> {
                    var i = read(Kind.I, work.unit().key(), Invocation.class);
                    yield i == null || i.conversation() == null ? null : i.conversation().conversationId();
                }
            };
            if (conversationId == null) return work.scope();
            var c = read(Kind.C, key(conversationId), Conversation.class);
            return c == null ? work.scope() : new Scope(c.owner(), c.conversationId());
        } catch (RuntimeException unavailable) {
            return work.scope();
        }
    }

    @Override
    public ReadBatch batch(List<Work> work) {
        if (work.size() > 100) throw new IllegalArgumentException("Eligibility batch exceeds limit");
        var groups = new LinkedHashMap<ExecutionOwner, Set<String>>();
        for (var item : work) {
            var scope = resolveScope(item);
            if (scope != null)
                groups.computeIfAbsent(scope.owner(), o -> new LinkedHashSet<>()).add(scope.conversationId());
        }
        var batch = new ReadBatch() {
            private final Map<Scope, Boolean> decisions = new HashMap<>();

            @Override
            public void refreshEligibility() {
                decisions.clear();
                groups.forEach((owner, ids) -> {
                    try {
                        var archived = archives.findArchivedConversationIds(owner, Set.copyOf(ids)).block(Duration.ofSeconds(5));
                        if (archived != null)
                            for (var id : ids) decisions.put(new Scope(owner, id), archived.contains(id));
                    } catch (RuntimeException unavailable) { /* 当前 owner 留作明确失败，不回退未归档 */ }
                });
            }

            @Override
            public Snapshot read(Unit unit, Scope previousScope) {
                return readCurrent(unit, previousScope, scope -> {
                    var decision = decisions.get(scope);
                    if (decision == null) throw new EligibilityUnavailable(scope);
                    return decision;
                });
            }
        };
        batch.refreshEligibility();
        return batch;
    }

    private Snapshot readCurrent(Unit unit, Scope previousScope, java.util.function.Predicate<Scope> archived) {
        Object value = switch (unit.kind()) {
            case C -> read(Kind.C, unit.key(), Conversation.class);
            case T -> read(Kind.T, unit.key(), Turn.class);
            case I -> read(Kind.I, unit.key(), Invocation.class);
        };
        if (value == null) return missing(unit, previousScope, archived);
        String conversationId = switch (value) {
            case Conversation c -> c.conversationId();
            case Turn t -> t.conversationId();
            case Invocation i -> i.conversation() == null ? null : i.conversation().conversationId();
            default -> throw new IllegalStateException("Unsupported source");
        };
        if (conversationId == null) return new Snapshot(unit, null, Disposition.SKIP, List.of());
        var c = value instanceof Conversation conversation ? conversation : read(Kind.C, key(conversationId), Conversation.class);
        if (c == null) return missing(unit, previousScope, archived);
        if (!key(c.conversationId()).equals(value instanceof Conversation ? unit.key() : key(conversationId)))
            throw new IllegalStateException("Conversation identity mismatch");
        var scope = new Scope(c.owner(), c.conversationId());
        if (previousScope != null && !previousScope.equals(scope))
            throw new IllegalStateException("Immutable source scope changed");
        if (archived.test(scope)) return new Snapshot(unit, scope, Disposition.ARCHIVED, List.of());
        var metadata = source.metadata(key(c.conversationId()));
        if (metadata != null && (!metadata.ownerKey().equals(key(c.owner().tenantId(), c.owner().workspaceId(), c.owner().subjectId()))
                || !metadata.conversationId().equals(c.conversationId())))
            throw new IllegalStateException("Metadata owner mismatch");
        if (c.state() != Conversation.State.ACTIVE || metadata != null && !metadata.active())
            return new Snapshot(unit, scope, Disposition.REMOVED, List.of());
        var records = new ArrayList<SearchIndexRecord>();
        if (value instanceof Conversation) {
            records.add(SearchIndexRecord.title(c.owner(), c.conversationId(), metadata == null ? c.title() : metadata.title(), c.createdAt(), 1));
        } else if (value instanceof Turn t) {
            if (!key(t.turnId()).equals(unit.key())) throw new IllegalStateException("Turn identity mismatch");
            String text = text(t.userMessage());
            if (!text.isBlank())
                records.add(SearchIndexRecord.message(c.owner(), c.conversationId(), t.turnId(), t.userMessage().messageId(), null, 0, t.sequence(), text, t.createdAt(), false, 1));
        } else if (value instanceof Invocation i) {
            if (!key(i.request().context().executionId()).equals(unit.key()) || !c.owner().equals(ExecutionOwner.from(i.request().context())))
                throw new IllegalStateException("Invocation owner mismatch");
            if (i.request().kind() != com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION)
                return new Snapshot(unit, scope, Disposition.LIVE, List.of());
            var turn = read(Kind.T, key(i.conversation().turnId()), Turn.class);
            int ordinal = turn == null ? -1 : turn.invocationIds().indexOf(i.request().context().executionId());
            if (turn != null && !turn.conversationId().equals(c.conversationId()))
                throw new IllegalStateException("Turn scope mismatch");
            if (ordinal >= 0 && i.state().terminal() && i.result() != null) {
                var ref = i.result();
                var row = payloads.result(ref.resultId());
                String ownerKey = key(c.owner().tenantId(), c.owner().workspaceId(), c.owner().subjectId());
                if (row == null) throw new MissingResult(scope);
                if (!ownerKey.equals(row.ownerKey()) || !unit.key().equals(row.invocationKey())
                        || !key(i.activeAttemptId()).equals(row.attemptKey()) || row.schemaVersion() != 1 || row.partial() < 0 || row.partial() > 1
                        || !ref.equals(new ResultRef(ref.resultId(), row.resultType(), row.schemaVersion(), row.payloadDigest(), row.partial() == 1))
                        || !CanonicalJson.sha256(row.workspaceSnapshot()).equals(row.payloadDigest()))
                    throw new IllegalStateException("Result integrity mismatch");
                var result = codec.decode(row.workspaceSnapshot(), InvocationResult.class);
                if (!(result instanceof InvocationResult.Generation))
                    throw new IllegalStateException("Generation result type mismatch");
                if (result instanceof InvocationResult.Generation generation) {
                    if (!"model-result".equals(ref.resultType()) || ref.partial() == generation.value().complete())
                        throw new IllegalStateException("Result completeness mismatch");
                    var micros = source.generatedMicros(key(c.conversationId()), key("ASSISTANT", i.request().context().executionId(), i.activeAttemptId()));
                    Instant generatedAt = micros == null ? null : Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
                    for (var message : generation.value().outputs()) {
                        String text = text(message);
                        if (!text.isBlank())
                            records.add(SearchIndexRecord.message(c.owner(), c.conversationId(), turn.turnId(), message.messageId(),
                                    i.request().context().executionId(), ordinal + 1, turn.sequence(), text, generatedAt, ref.partial(), 1).withSourceRef(ref.resultId()));
                    }
                }
            }
        }
        return new Snapshot(unit, scope, Disposition.LIVE, records);
    }

    private Snapshot missing(Unit unit, Scope scope, java.util.function.Predicate<Scope> archived) {
        return new Snapshot(unit, scope, scope != null && archived.test(scope) ? Disposition.ARCHIVED : Disposition.REMOVED, List.of());
    }

    private static String text(ChatMessage message) {
        return message.content().stream().filter(ChatMessage.Text.class::isInstance).map(ChatMessage.Text.class::cast)
                .map(ChatMessage.Text::text).collect(Collectors.joining("\n"));
    }

}
