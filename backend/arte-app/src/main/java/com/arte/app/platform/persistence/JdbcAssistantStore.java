package com.arte.app.platform.persistence;

import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.ainew.spi.assistant.AssistantStore;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;

public final class JdbcAssistantStore implements AssistantStore {
    private final JdbcTemplate jdbc;
    private final PublicTransactionRunner tx;
    private final PlatformJson json;

    public JdbcAssistantStore(DataSource source, PublicTransactionRunner tx, PlatformJson json) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = tx;
        this.json = json;
    }

    @Override
    public Optional<Resource> find(ResourceRef ref, boolean current) {
        if (!ASSISTANT.equals(ref.type())) return Optional.empty();
        return one("SELECT * FROM arte_ai_assistant_resource WHERE tenant_id=? AND assistant_id=?" + locking(current),
                this::resource, ref.tenantId(), ref.resourceId());
    }

    @Override
    public Optional<Resource> findByProfile(Scope scope, String profileId, boolean current) {
        return one("SELECT * FROM arte_ai_assistant_resource WHERE chat_profile_id=? AND tenant_id=? AND workspace_id=?" + locking(current),
                this::resource, profileId, scope.tenantId(), scope.workspaceId());
    }

    @Override
    public Optional<Draft> draft(String assistantId, boolean current) {
        return one("SELECT * FROM arte_ai_assistant_draft WHERE assistant_id=?" + locking(current),
                (row, index) -> new Draft(assistantId, row.getLong("draft_revision"), content(row), row.getString("updated_by"), time(row, "updated_at")),
                assistantId);
    }

    @Override
    public Optional<Release> release(String assistantId, long version) {
        return one("SELECT * FROM arte_ai_assistant_release WHERE assistant_id=? AND release_version=?"
                        + locking(tx.isTransactionActive() && !tx.isReadOnly()),
                this::release, assistantId, version);
    }

    @Override
    public Optional<Trial> trial(String assistantId, String trialId) {
        tx.requireWritable();
        return one("SELECT * FROM arte_ai_assistant_trial WHERE assistant_id=? AND trial_id=? FOR UPDATE",
                (row, index) -> new Trial(trialId, assistantId, row.getLong("draft_revision"), row.getString("actor_id"),
                        row.getString("content_digest"), row.getString("context_snapshot_id"), row.getString("invocation_id")),
                assistantId, trialId);
    }

    public record TrialCommand(Trial trial, String requestDigest) {
    }

    public Optional<TrialCommand> trialByKey(String key) {
        tx.requireWritable();
        return one("SELECT * FROM arte_ai_assistant_trial WHERE idempotency_digest=? FOR UPDATE",
                (row, index) -> new TrialCommand(new Trial(row.getString("trial_id"), row.getString("assistant_id"),
                        row.getLong("draft_revision"), row.getString("actor_id"), row.getString("content_digest"),
                        row.getString("context_snapshot_id"), row.getString("invocation_id")), row.getString("request_digest")), key);
    }

    public List<TrialHistory> trialHistory(String assistantId, String actorId, String after, int limit) {
        return jdbc.query("""
                SELECT trial_id,draft_revision,invocation_id,created_at FROM arte_ai_assistant_trial
                WHERE assistant_id=? AND actor_id=? AND trial_id>? ORDER BY trial_id LIMIT ?
                """, (row, index) -> new TrialHistory(row.getString("trial_id"), row.getLong("draft_revision"),
                row.getString("invocation_id"), time(row, "created_at")), assistantId, actorId, after == null ? "" : after, limit);
    }

    public void insertTrial(Trial trial, String key, String requestDigest, Instant createdAt) {
        tx.requireWritable();
        jdbc.update("""
                        INSERT INTO arte_ai_assistant_trial
                        (trial_id,assistant_id,draft_revision,actor_id,idempotency_digest,request_digest,content_digest,context_snapshot_id,invocation_id,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, trial.id(), trial.assistantId(), trial.draftRevision(), trial.actorId(), key, requestDigest,
                trial.contentDigest(), trial.snapshotId(), trial.invocationId(), utc(createdAt));
    }

    @Override
    public List<Resource> scan(Scope scope, String afterId, boolean activeOnly, int limit) {
        String active = activeOnly ? " AND state=" + State.ACTIVE.code() : "";
        return jdbc.query("SELECT * FROM arte_ai_assistant_resource WHERE tenant_id=? AND workspace_id=? AND assistant_id>?"
                        + active + " ORDER BY assistant_id LIMIT ?",
                this::resource, scope.tenantId(), scope.workspaceId(), afterId == null ? "" : afterId, limit);
    }

    @Override
    public List<Release> versions(String assistantId, long afterVersion, int limit) {
        return jdbc.query("SELECT * FROM arte_ai_assistant_release WHERE assistant_id=? AND release_version>?"
                + " ORDER BY release_version LIMIT ?" + locking(true), this::release, assistantId, afterVersion, limit);
    }

    @Override
    public void insert(Resource resource, Draft draft) {
        tx.requireWritable();
        jdbc.update("""
                        INSERT INTO arte_ai_assistant_resource
                        (assistant_id,tenant_id,workspace_id,chat_profile_id,state,current_release_version,version,created_by,created_at,updated_by,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """, resource.id(), resource.scope().tenantId(), resource.scope().workspaceId(), resource.profileId(),
                resource.state().code(), resource.releaseVersion(), resource.version(), resource.createdBy(), utc(resource.createdAt()),
                resource.updatedBy(), utc(resource.updatedAt()));
        Content content = draft.content();
        jdbc.update("""
                        INSERT INTO arte_ai_assistant_draft
                        (assistant_id,draft_revision,display_name,description,system_instruction,expected_output,example_prompts,updated_by,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, draft.assistantId(), draft.revision(), content.displayName(), content.description(), content.systemInstruction(),
                content.expectedOutput(), json.write(content.examplePrompts()), draft.updatedBy(), utc(draft.updatedAt()));
    }

    @Override
    public boolean replaceDraft(Draft draft, long expectedRevision) {
        tx.requireWritable();
        Content content = draft.content();
        return jdbc.update("""
                        UPDATE arte_ai_assistant_draft SET draft_revision=?,display_name=?,description=?,system_instruction=?,
                        expected_output=?,example_prompts=?,updated_by=?,updated_at=? WHERE assistant_id=? AND draft_revision=?
                        """, draft.revision(), content.displayName(), content.description(), content.systemInstruction(), content.expectedOutput(),
                json.write(content.examplePrompts()), draft.updatedBy(), utc(draft.updatedAt()), draft.assistantId(), expectedRevision) == 1;
    }

    @Override
    public boolean replaceResource(Resource resource, long expectedVersion) {
        tx.requireWritable();
        return jdbc.update("""
                        UPDATE arte_ai_assistant_resource SET state=?,current_release_version=?,version=?,updated_by=?,updated_at=?
                        WHERE assistant_id=? AND version=?
                        """, resource.state().code(), resource.releaseVersion(), resource.version(), resource.updatedBy(),
                utc(resource.updatedAt()), resource.id(), expectedVersion) == 1;
    }

    @Override
    public void insertRelease(Release release) {
        tx.requireWritable();
        Content content = release.content();
        jdbc.update("""
                        INSERT INTO arte_ai_assistant_release
                        (assistant_id,release_version,source_draft_revision,trial_id,schema_version,content_digest,published_by,published_at,
                        display_name,description,system_instruction,expected_output,example_prompts)
                        VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?)
                        """, release.assistantId(), release.version(), release.draftRevision(), release.trialId(), release.contentDigest(),
                release.publishedBy(), utc(release.publishedAt()), content.displayName(), content.description(), content.systemInstruction(),
                content.expectedOutput(), json.write(content.examplePrompts()));
    }

    private Resource resource(ResultSet row, int index) throws SQLException {
        return new Resource(row.getString("assistant_id"), new Scope(row.getString("tenant_id"), row.getString("workspace_id")),
                row.getString("chat_profile_id"), State.fromCode(row.getInt("state")), row.getObject("current_release_version", Long.class),
                row.getLong("version"), row.getString("created_by"), time(row, "created_at"), row.getString("updated_by"), time(row, "updated_at"));
    }

    private Release release(ResultSet row, int index) throws SQLException {
        Content content = content(row);
        String digest = row.getString("content_digest");
        if (row.getInt("schema_version") != 1 || !digest.equals(DefaultAssistantManager.contentDigest(content))) {
            throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "assistant-release");
        }
        return new Release(row.getString("assistant_id"), row.getLong("release_version"), row.getLong("source_draft_revision"),
                row.getString("trial_id"), digest, content, row.getString("published_by"), time(row, "published_at"));
    }

    private Content content(ResultSet row) throws SQLException {
        return new Content(row.getString("display_name"), row.getString("description"), row.getString("system_instruction"),
                row.getString("expected_output"), List.of(json.read(row.getString("example_prompts"), String[].class)));
    }

    private String locking(boolean current) {
        if (!current) return "";
        tx.requireWritable();
        return " FOR UPDATE";
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private static Instant time(ResultSet row, String field) throws SQLException {
        return row.getObject(field, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
