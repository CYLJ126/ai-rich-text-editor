package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.conversation.ConversationText;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.AdmissionMapper;
import com.arte.ainew.persistence.mybatis.mapper.ConversationMetadataMapper;
import com.arte.ainew.persistence.mybatis.mapper.EventMapper;
import com.arte.ainew.persistence.mybatis.mapper.ExecutionMapper;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.ExecutionPayload;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;

/**
 * 同一 JDBC 事务内使用；会话行锁保护命名与消息投影，owner 锁保护 7 个置顶名额。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
final class ConversationMetadataPersistence {
    private final ConversationMetadataMapper metadata;
    private final AdmissionMapper admission;
    private final ExecutionMapper execution;
    private final EventMapper events;
    private final ExecutionRecordCodec codec;
    private final Consumer<String> lock;
    private final Supplier<Instant> now;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    ConversationMetadataPersistence(ConversationMetadataMapper metadata, AdmissionMapper admission,
                                    ExecutionMapper execution, EventMapper events, ExecutionRecordCodec codec,
                                    Consumer<String> lock, Supplier<Instant> now) {
        this.metadata = metadata;
        this.admission = admission;
        this.execution = execution;
        this.events = events;
        this.codec = codec;
        this.lock = lock;
        this.now = now;
    }

    private static String key(String... parts) {
        return CanonicalJson.key(parts);
    }

    private static String ownerKey(ExecutionOwner owner) {
        return key(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    static long micros(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000), at.getNano() / 1000);
    }

    private static Instant instant(Long micros) {
        return micros == null ? null : Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
    }

    static ConversationList.Item item(ConversationMetadataMapper.Row r) {
        return new ConversationList.Item(r.conversationId(), r.title(), r.metadataVersion(), r.pinned(),
                r.titleManuallyEdited(), r.autoTitleResolved(), r.previewText(), r.userMessageCount(),
                instant(r.lastMessageMicros()), instant(r.sortMicros()), instant(r.createdMicros()), r.statisticsReady());
    }

    /**
     * 调用前持有会话行锁，或为尚不可见的新会话。旧记录不能再次自动改名。
     */
    void initialize(Conversation c, boolean fresh) {
        var id = key(c.conversationId());
        var owner = ownerKey(c.owner());
        if (metadata.findCurrent(id, owner) != null) return;
        var created = micros(c.createdAt());
        boolean empty = fresh || admission.maximumTurnSequence(id) == 0;
        metadata.insert(new ConversationMetadataMapper.Row(id, owner, c.conversationId(), c.title(), 0,
                false, !fresh, !fresh, null, empty ? 0L : null, null, created, created, empty), c.state() == Conversation.State.ACTIVE);
        if (!empty) metadata.enqueue(id);
    }

    ConversationList.Item find(ExecutionOwner owner, String id) {
        ContractChecks.id(id, "conversationId");
        var row = metadata.find(key(id), ownerKey(owner));
        return row == null ? null : item(row);
    }

    StoreOutcome<ConversationList.Receipt> change(ExecutionOwner owner, ConversationList.Command c) {
        var ownerKey = ownerKey(owner);
        var commandKey = key(c.idempotencyKey());
        var digest = CanonicalJson.digest(List.of(c.conversationId(), c.expectedMetadataVersion(),
                c.title() == null ? "setPinned" : "rename", c.title() == null ? c.pinned().toString() : c.title()));
        lock.accept(key("conversation-metadata-command", ownerKey, commandKey));
        var previous = metadata.command(ownerKey, commandKey);
        if (previous != null) {
            if (!previous.requestDigest().equals(digest)) return StoreOutcome.rejected(IDEMPOTENCY_CONFLICT);
            var original = codec.decode(previous.workspaceSnapshot(), ConversationList.CommandResult.class);
            return original.receipt() == null ? StoreOutcome.rejected(original.outcome()) : StoreOutcome.replayed(original.receipt());
        }
        // 名额锁先于会话锁；调用方 READ_COMMITTED 事务保证取得锁后的 count 可见前一命令。
        lock.accept(key("conversation-pins", ownerKey));
        var id = key(c.conversationId());
        var snapshot = admission.conversationSnapshot(id, true);
        if (snapshot == null) return reject(ownerKey, commandKey, digest, c, NOT_FOUND);
        var conversation = codec.decode(snapshot, Conversation.class);
        if (!conversation.owner().equals(owner) || conversation.state() != Conversation.State.ACTIVE)
            return reject(ownerKey, commandKey, digest, c, NOT_FOUND);
        initialize(conversation, false);
        var r = metadata.findCurrent(id, ownerKey);
        if (r.metadataVersion() != c.expectedMetadataVersion())
            return reject(ownerKey, commandKey, digest, c, VERSION_CONFLICT);
        var pinCount = metadata.count(ownerKey, true);
        if (Boolean.TRUE.equals(c.pinned()) && !r.pinned() && pinCount >= 7)
            return reject(ownerKey, commandKey, digest, c, PIN_LIMIT_REACHED);
        boolean changed = c.title() != null ? !c.title().equals(r.title()) : c.pinned() != r.pinned();
        if (changed) {
            var updated = new ConversationMetadataMapper.Row(r.idKey(), r.ownerKey(), r.conversationId(),
                    c.title() == null ? r.title() : c.title(), Math.addExact(r.metadataVersion(), 1),
                    c.pinned() == null ? r.pinned() : c.pinned(), c.title() != null || r.titleManuallyEdited(),
                    r.autoTitleResolved(), r.previewText(), r.userMessageCount(), r.lastMessageMicros(), r.sortMicros(),
                    r.createdMicros(), r.statisticsReady());
            metadata.saveMetadata(updated);
            if (c.title() != null) saveTitle(conversation, updated.title());
            if (c.pinned() != null) pinCount += c.pinned() ? 1 : -1;
            r = updated;
        }
        var receipt = new ConversationList.Receipt(c.conversationId(), c.idempotencyKey(), item(r), pinCount, changed);
        metadata.insertCommand(ownerKey, commandKey, digest, codec.encode(new ConversationList.CommandResult(c.idempotencyKey(), APPLIED, receipt)));
        return StoreOutcome.applied(receipt);
    }

    private StoreOutcome<ConversationList.Receipt> reject(String owner, String commandKey, String digest,
                                                          ConversationList.Command command, StoreOutcome.Code code) {
        metadata.insertCommand(owner, commandKey, digest, codec.encode(new ConversationList.CommandResult(command.idempotencyKey(), code, null)));
        return StoreOutcome.rejected(code);
    }

    private void saveTitle(Conversation c, String title) {
        var id = key(c.conversationId());
        // 保存最新锁内快照，保留执行版本和当前调用门闩。
        var at = now.get();
        if (at.isBefore(c.updatedAt())) at = c.updatedAt();
        var changed = new Conversation(c.conversationId(), c.owner(), title, c.version(), c.chatProfile(), c.resources(),
                c.state(), c.createdAt(), at);
        admission.advanceConversation(c.version(), admission.conversationGateCurrent(id).activeInvocation(), codec.encode(changed), id);
    }

    ConversationList.CommandResult receipt(ExecutionOwner owner, String commandId) {
        ContractChecks.id(commandId, "commandId");
        var row = metadata.command(ownerKey(owner), key(commandId));
        return row == null ? null : codec.decode(row.workspaceSnapshot(), ConversationList.CommandResult.class);
    }

    /**
     * 新用户消息与 Turn 在同事务提交；重新生成不调用此方法。返回自动命名后的当前标题。
     */
    String userMessage(Conversation c, Turn turn) {
        var id = key(c.conversationId());
        var owner = ownerKey(c.owner());
        initialize(c, false);
        var r = metadata.findCurrent(id, owner);
        var fact = key("USER", turn.userMessage().messageId());
        if (metadata.factCurrent(id, fact) != null) return r.title();
        var generated = micros(turn.createdAt());
        metadata.insertFact(id, fact, "USER", generated);
        var question = ConversationText.question(turn.userMessage());
        var count = r.userMessageCount() == null ? null : Math.addExact(r.userMessageCount(), 1);
        var last = r.lastMessageMicros() == null ? generated : Math.max(generated, r.lastMessageMicros());
        var updated = new ConversationMetadataMapper.Row(r.idKey(), r.ownerKey(), r.conversationId(), r.title(),
                r.metadataVersion(), r.pinned(), r.titleManuallyEdited(), r.autoTitleResolved(),
                ConversationText.shorten(question, 30), count, last, last, r.createdMicros(), r.statisticsReady());
        metadata.saveStatistics(updated);
        metadata.savePreviewSequence(id, turn.sequence());
        if (!r.autoTitleResolved()) {
            var title = r.titleManuallyEdited() || question.isBlank() ? r.title() : ConversationText.shorten(question, 20);
            metadata.saveMetadata(new ConversationMetadataMapper.Row(r.idKey(), r.ownerKey(), r.conversationId(), title,
                    title.equals(r.title()) ? r.metadataVersion() : Math.addExact(r.metadataVersion(), 1), r.pinned(), r.titleManuallyEdited(), true,
                    updated.previewText(), count, last, updated.sortMicros(), r.createdMicros(), r.statisticsReady()));
            return title;
        }
        return r.title();
    }

    /**
     * 每个 assistant attempt 的首条可见文本事件作为该消息生成时间；以后流式批次只查去重事实。
     */
    void assistantMessage(Invocation invocation, List<ExecutionEvent<?>> saved) {
        if (invocation.conversation() == null) return;
        var visible = saved.stream().filter(e -> e.payload() instanceof ExecutionPayload.OutputBatch batch
                && batch.events().stream().anyMatch(GenerationEvent.TextDelta.class::isInstance)).findFirst();
        if (visible.isEmpty()) return;
        var e = visible.get();
        var id = key(invocation.conversation().conversationId());
        var fact = key("ASSISTANT", e.executionId(), e.attemptId());
        // 同 invocation 的 append 已由调用锁串行；避免每字符重新写 metadata。
        if (metadata.fact(id, fact) != null) return;
        var snapshot = admission.conversationSnapshot(id, true);
        if (snapshot == null) return;
        var c = codec.decode(snapshot, Conversation.class);
        initialize(c, false);
        if (metadata.factCurrent(id, fact) != null) return;
        metadata.insertFact(id, fact, "ASSISTANT", micros(e.occurredAt()));
        var r = metadata.findCurrent(id, ownerKey(c.owner()));
        var last = r.lastMessageMicros() == null ? micros(e.occurredAt()) : Math.max(r.lastMessageMicros(), micros(e.occurredAt()));
        metadata.saveStatistics(new ConversationMetadataMapper.Row(r.idKey(), r.ownerKey(), r.conversationId(), r.title(),
                r.metadataVersion(), r.pinned(), r.titleManuallyEdited(), r.autoTitleResolved(), r.previewText(),
                r.userMessageCount(), last, last, r.createdMicros(), r.statisticsReady()));
    }

    private record Cursor(int version, String owner, boolean pinned, long sortMicros, String conversationId) {
    }

    private Cursor decode(String cursor, String owner, boolean pinned) {
        try {
            var value = JSON.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
            ContractChecks.id(value.conversationId(), "cursor conversationId");
            ContractChecks.require(value.version() == 1 && value.owner().equals(owner) && value.pinned() == pinned,
                    "Cursor scope or filter mismatch");
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid conversation cursor", invalid);
        }
    }

    private String encode(ConversationMetadataMapper.Row row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsString(
                new Cursor(1, row.ownerKey(), row.pinned(), row.sortMicros(), row.conversationId())).getBytes(StandardCharsets.UTF_8));
    }

    private ConversationList.Page page(String owner, boolean pinned, int size, Long sort, String id,
                                       boolean inclusive, boolean total) {
        var rows = metadata.list(owner, pinned, sort, id, inclusive, size + 1);
        boolean more = rows.size() > size;
        var kept = more ? rows.subList(0, size) : rows;
        return new ConversationList.Page(kept.stream().map(ConversationMetadataPersistence::item).toList(),
                more ? encode(kept.getLast()) : null, more, total ? metadata.count(owner, pinned) : null);
    }

    ConversationList.Page list(ExecutionOwner owner, ConversationList.Query query) {
        var scope = ownerKey(owner);
        var cursor = query.cursor() == null ? null : decode(query.cursor(), scope, query.pinned());
        return page(scope, query.pinned(), query.size(), cursor == null ? null : cursor.sortMicros(),
                cursor == null ? null : cursor.conversationId(), false, query.includeTotal());
    }

    /**
     * 调用方使用一段 REPEATABLE_READ 只读事务；两区不能在不同快照读取。
     */
    ConversationList.Window window(ExecutionOwner owner, ConversationList.WindowQuery query) {
        var scope = ownerKey(owner);
        var pinned = page(scope, true, 7, null, null, false, true);
        var anchor = query.anchorConversationId() == null ? null : metadata.find(key(query.anchorConversationId()), scope);
        boolean fallback = query.anchorConversationId() != null && (anchor == null || anchor.pinned());
        if (fallback) anchor = null;
        var normal = page(scope, false, Math.min(query.normalSize(), 100 - pinned.records().size()),
                anchor == null ? null : anchor.sortMicros(), anchor == null ? null : anchor.conversationId(), true, true);
        return new ConversationList.Window(pinned, normal, anchor == null ? null : anchor.conversationId(), fallback);
    }

    /**
     * 每次最多初始化 limit 条、推进 limit 个任务，每任务只读取一轮或 100 个历史事件；不在列表请求中执行。
     */
    ConversationList.BackfillProgress backfill(int limit) {
        ContractChecks.range(limit, "limit", 1, 100);
        var missing = metadata.missing(null, limit);
        for (var legacy : missing) {
            var snapshot = admission.conversationSnapshot(legacy.idKey(), true);
            if (snapshot != null) {
                var c = codec.decode(snapshot, Conversation.class);
                initialize(c, false);
            }
        }
        var jobs = metadata.jobs(limit);
        for (var job : jobs) rebuildStep(job);
        return new ConversationList.BackfillProgress(missing.size() + jobs.size(),
                !metadata.missing(null, 1).isEmpty() || !metadata.jobs(1).isEmpty());
    }

    private void rebuildStep(ConversationMetadataMapper.Job queued) {
        var snapshot = admission.conversationSnapshot(queued.idKey(), true);
        if (snapshot == null) {
            metadata.finishJob(queued.idKey());
            return;
        }
        var c = codec.decode(snapshot, Conversation.class);
        var job = metadata.job(queued.idKey());
        if (job == null) return;
        var next = admission.turnsAfter(queued.idKey(), job.turnSequence(), 1);
        if (next.isEmpty()) {
            var r = metadata.findCurrent(queued.idKey(), ownerKey(c.owner()));
            var last = metadata.lastFact(queued.idKey());
            // 已裁剪且无法恢复的助手生成时间明确未知，不用终态更新时间伪造。
            metadata.saveStatistics(new ConversationMetadataMapper.Row(r.idKey(), r.ownerKey(), r.conversationId(), r.title(),
                    r.metadataVersion(), r.pinned(), r.titleManuallyEdited(), r.autoTitleResolved(), r.previewText(),
                    metadata.userFacts(queued.idKey()), job.incomplete() ? null : last,
                    last == null ? r.createdMicros() : last, r.createdMicros(), !job.incomplete()));
            metadata.finishJob(queued.idKey());
            return;
        }
        var turn = codec.decode(next.getFirst(), Turn.class);
        var fact = key("USER", turn.userMessage().messageId());
        if (metadata.factCurrent(queued.idKey(), fact) == null)
            metadata.insertFact(queued.idKey(), fact, "USER", micros(turn.createdAt()));
        metadata.backfillPreview(queued.idKey(), turn.sequence(), ConversationText.shorten(ConversationText.question(turn.userMessage()), 30));
        if (job.invocationIndex() >= turn.invocationIds().size()) {
            metadata.saveJob(new ConversationMetadataMapper.Job(job.idKey(), turn.sequence(), 0, 0, job.incomplete()));
            return;
        }
        var invocationKey = key(turn.invocationIds().get(job.invocationIndex()));
        var invSnapshot = execution.invocationSnapshot(invocationKey, false);
        if (invSnapshot == null) {
            metadata.saveJob(new ConversationMetadataMapper.Job(job.idKey(), job.turnSequence(), job.invocationIndex() + 1, 0, true));
            return;
        }
        var invocation = codec.decode(invSnapshot, Invocation.class);
        var retained = events.retainedAfter(invocationKey);
        var batch = events.events(invocationKey, Math.max(job.eventSequence(), retained), 100);
        for (var json : batch) {
            var e = codec.decode(json, ExecutionEvent.class);
            if (e.payload() instanceof ExecutionPayload.OutputBatch output
                    && output.events().stream().anyMatch(GenerationEvent.TextDelta.class::isInstance)) {
                var assistant = key("ASSISTANT", e.executionId(), e.attemptId());
                if (metadata.factCurrent(job.idKey(), assistant) == null)
                    metadata.insertFact(job.idKey(), assistant, "ASSISTANT", micros(e.occurredAt()));
            }
        }
        var incomplete = job.incomplete() || retained > 0;
        if (batch.size() < 100)
            metadata.saveJob(new ConversationMetadataMapper.Job(job.idKey(), job.turnSequence(), job.invocationIndex() + 1, 0, incomplete));
        else metadata.saveJob(new ConversationMetadataMapper.Job(job.idKey(), job.turnSequence(), job.invocationIndex(),
                codec.decode(batch.getLast(), ExecutionEvent.class).sequence(), incomplete));
    }
}
