package com.arte.core.pojo.platform;

import com.arte.core.enums.access.*;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.CleanupAuthorizationStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.workspace.CleanupAuthorization;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.VerifiedCleanupAuthorization;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.ARTICLE_READ;
import static com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE;
import static org.junit.jupiter.api.Assertions.*;

class PlatformContractTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Scope SCOPE = new Scope("tenant-a", "workspace-a");
    private static final CommandMeta META = new CommandMeta("operation-key", "trace-id");

    @Test
    void ownershipCannotRepresentPersonalOwnerOrContradictItsRange() {
        assertThrows(IllegalArgumentException.class, () -> ResourceOwnershipTypeEnum.fromValue(2));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceOwnership(ResourceOwnershipTypeEnum.TENANT, "tenant-a", "workspace-a"));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE, "tenant-a", null));
        var ref = new com.arte.core.pojo.access.ResourceRef("tenant-a", ARTICLE, "article-a");
        assertThrows(IllegalArgumentException.class, () -> new ResourceDescriptor(ref,
                new ResourceOwnership(ResourceOwnershipTypeEnum.TENANT, "tenant-b", null), "ACTIVE", 0, "creator"));
    }

    @Test
    void scopesAndResourceRequestsPreserveTenantIsolation() {
        assertNotEquals(SCOPE, new Scope("tenant-b", "workspace-a"));
        assertThrows(IllegalArgumentException.class, () -> new AccessRequest(SCOPE,
                new com.arte.core.pojo.access.ResourceRef("tenant-b", ARTICLE, "article-a"), ARTICLE_READ, "read"));
        assertThrows(IllegalArgumentException.class, () -> new Scope(" tenant-a", "workspace-a"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("tenant-a", "workspace-a\n"));
        assertThrows(IllegalArgumentException.class, () -> new CommandScope(null, "workspace-a"));
        assertNotEquals(new CommandScope(null, null), new CommandScope("tenant-a", null));
    }

    @Test
    void persistedSubjectGrantsIncludeMembershipGenerationButWriteRecipientDoesNot() {
        var first = new GrantTarget.Subject("user-a", new CommandScope("tenant-a", "workspace-a"), 0);
        var rejoined = new GrantTarget.Subject("user-a", new CommandScope("tenant-a", "workspace-a"), 1);
        assertNotEquals(first, rejoined);
        assertEquals(List.of("subjectId"), java.util.Arrays.stream(GrantRecipient.Subject.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        var ref = new com.arte.core.pojo.access.ResourceRef("tenant-a", ARTICLE, "article-a");
        var grant = new ResourceGrant("grant-a", ref, first, ARTICLE_READ, GrantStateEnum.ACTIVE, null,
                GrantSourceEnum.SHARE, "share-operation", "issuer", NOW, 0);
        assertEquals(0, ((GrantTarget.Subject) grant.target()).membershipGeneration());
        assertThrows(IllegalArgumentException.class, () -> new ResourceGrant("grant-b", ref,
                new GrantTarget.Space(new Scope("tenant-b", "workspace-a")), ARTICLE_READ, GrantStateEnum.ACTIVE,
                null, GrantSourceEnum.SHARE, "share-operation", "issuer", NOW, 0));
    }

    @Test
    void ordinaryRoleCommandsCannotPerformOwnerTransfer() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceCommands.InviteMember(
                META, SCOPE, 0, "target", WorkspaceRoleEnum.OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceCommands.InviteMember(
                META, SCOPE, 0, "target", WorkspaceRoleEnum.ADMIN, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceCommands.ChangeMemberRole(
                META, SCOPE, "member", 0, 0, WorkspaceRoleEnum.OWNER));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceCommands.RenameWorkspace(
                META, SCOPE, -1, "name", ""));
        var create = new WorkspaceCommands.CreateWorkspace(META, "tenant-a", "  我的空间  ", null);
        assertEquals("我的空间", create.name());
        assertEquals("", create.description());
    }

    @Test
    void policiesDeepCopyNestedRolesAndDoNotTurnEmptyListsIntoUnrestrictedAccess() {
        var roles = EnumSet.of(WorkspaceRoleEnum.MEMBER);
        var extra = new HashMap<com.arte.core.pojo.access.ResourceType, Set<WorkspaceRoleEnum>>();
        extra.put(ARTICLE, roles);
        var types = new HashSet<>(Set.of(ARTICLE));
        var rules = new PolicyRules(types, extra, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(GrantTargetKindEnum.SUBJECT));
        roles.add(WorkspaceRoleEnum.GUEST);
        extra.clear();
        types.clear();
        assertEquals(Set.of(ARTICLE), rules.allowedCreationTypes());
        assertEquals(Set.of(WorkspaceRoleEnum.MEMBER), rules.extraCreationRoles().get(ARTICLE));
        assertThrows(UnsupportedOperationException.class,
                () -> rules.extraCreationRoles().get(ARTICLE).add(WorkspaceRoleEnum.OWNER));
        assertTrue(rules.allowedDestinations().isEmpty());
        assertFalse(rules.personalCredentialsAllowed());
    }

    @Test
    void sharedVisibilityRequiresExplicitRoleOrSpaceActions() {
        var ref = new com.arte.core.pojo.access.ResourceRef("tenant-a", ARTICLE, "article-a");
        assertThrows(IllegalArgumentException.class, () -> new ResourceAccessCommands.ChangeVisibility(
                META, SCOPE, ref, 0, ResourceVisibilityEnum.SPACE_SHARED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new VisibilityGrant(
                new GrantRecipient.Subject("user-a"), Set.of(ARTICLE_READ), null));
        var share = new VisibilityGrant(new GrantRecipient.SpaceRole(SCOPE, WorkspaceRoleEnum.MEMBER),
                Set.of(ARTICLE_READ), null);
        var source = new ArrayList<>(List.of(share));
        var command = new ResourceAccessCommands.ChangeVisibility(META, SCOPE, ref, 0,
                ResourceVisibilityEnum.SPACE_SHARED, source);
        source.clear();
        assertEquals(List.of(share), command.sharedGrants());
        assertThrows(IllegalArgumentException.class, () -> new ResourceAccessCommands.ChangeVisibility(
                META, SCOPE, ref, 0, ResourceVisibilityEnum.EXPLICIT, List.of(share)));
    }

    @Test
    void actionValueObjectRejectsWildcardsAndDoesNotSupplyAnAllowConclusion() {
        assertThrows(IllegalArgumentException.class, () -> new Action("article:*"));
        assertThrows(IllegalArgumentException.class, () -> new Action("read"));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessDecision(true, AccessReasonEnum.ACTION_NOT_GRANTED, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessDecision(true, AccessReasonEnum.ALLOWED, null, NOW));
        var denied = new AccessDecision(false, AccessReasonEnum.UNKNOWN_ACTION, null, NOW);
        assertFalse(denied.allowed());
        assertNull(denied.versions());
    }

    @Test
    void receiptStateAndCompletionFactsStayConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new CommandReceipt<>("operation", CommandStatusEnum.APPLIED,
                CommandDeliveryEnum.APPLIED, null, 0, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new CommandReceipt<>("operation", CommandStatusEnum.PENDING,
                CommandDeliveryEnum.APPLIED, null, 0, NOW, NOW));
        var receipt = new CommandReceipt<Void>("operation", CommandStatusEnum.APPLIED,
                CommandDeliveryEnum.REPLAYED, null, 1, NOW, NOW);
        assertEquals(CommandDeliveryEnum.REPLAYED, receipt.delivery());
        assertNull(receipt.value());
    }

    @Test
    void cleanupSnapshotRequiresActiveBoundServiceAndUnexpiredAuthorization() {
        var authorization = new CleanupAuthorization("authorization", "service-a", SCOPE, "closure", "ai",
                Set.of(new Action("ai:cleanup:cancel")), "work", Set.of("invocation"), NOW.plusSeconds(300),
                CleanupAuthorizationStateEnum.ACTIVE, 0);
        var service = new Principal("service-a", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE);
        assertEquals(service, new VerifiedCleanupAuthorization(authorization, service, NOW).actor());
        assertThrows(IllegalArgumentException.class, () -> new VerifiedCleanupAuthorization(authorization,
                new Principal("service-a", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), NOW));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedCleanupAuthorization(authorization,
                new Principal("service-b", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedCleanupAuthorization(authorization, service, authorization.expiresAt()));
    }

    @Test
    void identityMappingCannotDiscardStableIdOrRetireBeforeCreation() {
        var identity = new PrincipalIdentity("subject-a", "rbac", "123", NOW, NOW.plusSeconds(1), 1);
        assertEquals("subject-a", identity.subjectId());
        assertEquals("123", identity.externalUserId());
        assertThrows(IllegalArgumentException.class,
                () -> new PrincipalIdentity("subject-a", "rbac", "123", NOW, NOW.minusSeconds(1), 1));
        assertEquals(List.of("subjectId", "provider", "externalUserId", "createdAt", "retiredAt", "version"),
                java.util.Arrays.stream(PrincipalIdentity.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test
    void pagesAreBoundedAndDefensiveAndRejectOffsetOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, 101));
        assertThrows(ArithmeticException.class, () -> new PageQuery(Long.MAX_VALUE, 100));
        var list = new ArrayList<>(List.of("item"));
        var page = new PageResult<>(1, 20, 1, list);
        list.clear();
        assertEquals(List.of("item"), page.records());
        assertThrows(UnsupportedOperationException.class, () -> page.records().clear());
    }

    @Test
    void transactionCollaboratorsCanRequireActualCallerTransaction() {
        var runner = new PublicTransactionRunner() {
            @Override
            public <T> T required(Supplier<T> work) {
                return work.get();
            }

            @Override
            public boolean isTransactionActive() {
                return false;
            }
        };
        assertThrows(IllegalStateException.class, runner::requireActive);
    }
}
