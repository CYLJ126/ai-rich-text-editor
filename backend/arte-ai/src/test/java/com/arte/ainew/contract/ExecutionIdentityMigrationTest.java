package com.arte.ainew.contract;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.value.StructuredValue;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 迁移前固定样本验证执行身份、归属、编码与幂等键，禁止用当前实现重建预期样本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/28 17:51 ✾
 */
public class ExecutionIdentityMigrationTest {

    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    public void oldUserAndServiceInvocationsKeepPrincipalAndContextBytes() throws IOException {
        var vectors = json.readTree(resource("vectors.json"));
        for (String kind : List.of("user", "service")) {
            var invocation = assertSnapshot("invocation-" + kind + ".json", Invocation.class);
            var context = invocation.request().context();
            assertEquals(ExecutionPrincipal.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)), context.authorization().principal().kind());
            assertEquals("stable-subject", context.authorization().principal().subjectId());
            assertEquals(vectors.path("principal-" + kind).asString(), CanonicalJson.encode(context.authorization().principal()));
            assertEquals(vectors.path("context-" + kind).asString(), CanonicalJson.encode(context));
            assertEquals(new ExecutionOwner("tenant", "workspace", "stable-subject"), ExecutionOwners.from(context));
        }
    }

    @Test
    public void oldConversationDecodesWithThePublicOwnerAndUnchangedSnapshot() throws IOException {
        var conversation = assertSnapshot("conversation.json", Conversation.class);
        assertEquals(new ExecutionOwner("tenant", "workspace", "stable-subject"), conversation.owner());
        assertEquals(ExecutionOwner.class, conversation.owner().getClass());
    }

    @Test
    public void ownerDigestsSearchKeysAndToolIdempotencyRemainExactlyTheSame() throws IOException {
        var vectors = json.readTree(resource("vectors.json"));
        var owner = new ExecutionOwner("tenant", "workspace", "stable-subject");
        assertEquals(vectors.path("owner").asString(), CanonicalJson.encode(owner));
        assertEquals(vectors.path("ownerDigest").asString(), CanonicalJson.digest(owner));
        assertEquals(vectors.path("searchOwner").asString(), SearchCursor.owner(owner));
        assertEquals(vectors.path("toolCallId").asString(), ToolAdmissionDigests.callId(owner, "binding", "idempotency"));
        var input = new ToolAdmissionInput("binding", new StructuredValue.ObjectValue(Map.of("text", new StructuredValue.StringValue("中文😀"))), Duration.ofSeconds(30), 1, 4096L);
        assertEquals(vectors.path("submissionDigest").asString(), ToolAdmissionDigests.submission(owner, input));
    }

    @Test
    public void fixedGrantAndCoreEnumKeepTheirDifferentExistingWireFormats() throws IOException {
        String original = CanonicalJson.normalize(resource("fixed-grant.json"));
        var grant = json.readValue(original, NewAiProperties.Grant.class);
        assertEquals(ExecutionPrincipal.Kind.USER, grant.principalKind());
        assertEquals(original, CanonicalJson.encode(grant));
        assertEquals("0", json.writeValueAsString(PrincipalKindEnum.USER));
        assertEquals("1", json.writeValueAsString(PrincipalKindEnum.SERVICE));
        assertEquals("\"USER\"", json.writeValueAsString(ExecutionPrincipal.Kind.USER));
        assertEquals("\"SERVICE\"", json.writeValueAsString(ExecutionPrincipal.Kind.SERVICE));
    }

    @Test
    public void currentUserAndServiceKindsMapExplicitlyIntoExecutionSnapshots() {
        for (var kind : PrincipalKindEnum.values()) {
            var current = new Principal("stable-subject", kind, PrincipalStatusEnum.ACTIVE);
            var execution = ExecutionPrincipal.from(current, "authenticated-name");
            assertEquals(current.subjectId(), execution.subjectId());
            assertEquals(kind, execution.kind().platformKind());
            assertEquals(execution.kind(), ExecutionPrincipal.Kind.from(kind));
            assertTrue(execution.sameIdentityAs(current));
        }
        assertThrows(NullPointerException.class, () -> ExecutionPrincipal.Kind.from(null));
    }

    @Test
    public void identityEqualityDoesNotTurnHistoricalSnapshotsIntoActiveAuthorization() {
        var snapshot = new ExecutionPrincipal("stable-subject", "old-name", ExecutionPrincipal.Kind.USER);
        for (var status : List.of(PrincipalStatusEnum.SUSPENDED, PrincipalStatusEnum.DELETED)) {
            var current = new Principal("stable-subject", PrincipalKindEnum.USER, status);
            assertTrue(snapshot.sameIdentityAs(current));
            assertThrows(IllegalArgumentException.class, () -> ExecutionPrincipal.from(current, "current-name"));
        }
        assertFalse(snapshot.sameIdentityAs(new Principal("other-subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE)));
        assertFalse(snapshot.sameIdentityAs(new Principal("stable-subject", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE)));
        assertFalse(snapshot.sameIdentityAs(null));
    }

    @Test
    public void renameReauthorizationAndChildExecutionDoNotChangeOwnership() throws IOException {
        var context = codec.decode(resource("invocation-user.json"), Invocation.class).request().context();
        var current = new Principal("stable-subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE);
        var renamed = ExecutionPrincipal.from(current, "renamed");
        assertTrue(context.authorization().principal().sameIdentityAs(current));
        var reauthorized = withPrincipal(context, renamed);
        assertEquals(ExecutionOwners.from(context), ExecutionOwners.from(reauthorized));
        assertEquals(ExecutionOwners.from(context), ExecutionOwners.from(context.child("child", context.deadline(), Set.of("ai:invoke"), "child-key")));
        var other = ExecutionPrincipal.from(new Principal("another-subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), "alice");
        assertNotEquals(ExecutionOwners.from(context), ExecutionOwners.from(withPrincipal(context, other)));
        assertThrows(NullPointerException.class, () -> ExecutionOwners.from(null));
    }

    @Test
    public void historicalPrincipalReadDoesNotInventStatusOrTightenOldTextValidation() {
        var old = new ExecutionPrincipal("id".repeat(200), " name ", ExecutionPrincipal.Kind.USER);
        assertEquals(old, json.readValue(CanonicalJson.encode(old), ExecutionPrincipal.class));
        var tree = json.readTree(CanonicalJson.encode(old));
        assertEquals(3, tree.size());
        assertFalse(tree.has("status"));
        assertFalse(tree.has("platformKind"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPrincipal(" ", "name", ExecutionPrincipal.Kind.USER));
    }

    private ExecutionContext withPrincipal(ExecutionContext context, ExecutionPrincipal principal) {
        var authorization = context.authorization();
        return new ExecutionContext(context.executionId(), context.traceId(), new ExecutionAuthorization(principal, authorization.tenantId(), authorization.workspaceId(), authorization.scopes(), authorization.grantRef()), context.deadline(), context.parentExecutionId(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
    }

    private <T> T assertSnapshot(String file, Class<T> type) throws IOException {
        String oldBytes = CanonicalJson.normalize(resource(file));
        T value = codec.decode(oldBytes, type);
        assertEquals(oldBytes, CanonicalJson.normalize(codec.encode(value)));
        return value;
    }

    private static String resource(String file) throws IOException {
        try (var stream = Objects.requireNonNull(ExecutionIdentityMigrationTest.class.getResourceAsStream("/identity-contract-v1/" + file))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
