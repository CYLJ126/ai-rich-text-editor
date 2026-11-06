package com.arte.ainew.pojo.assistant;

import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.access.ResourceOwnership;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;
import java.util.List;

import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;

public final class Assistants {

    private Assistants() {
    }

    public enum State {
        DRAFT(0), ACTIVE(1), DISABLED(2);

        private final int code;

        State(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static State fromCode(int code) {
            return switch (code) {
                case 0 -> DRAFT;
                case 1 -> ACTIVE;
                case 2 -> DISABLED;
                default -> throw new IllegalArgumentException("Invalid assistant state");
            };
        }
    }

    public record Content(String displayName, String description, String systemInstruction,
                          String expectedOutput, List<String> examplePrompts) {
        public Content {
            displayName = clean(displayName);
            description = clean(description);
            systemInstruction = clean(systemInstruction);
            expectedOutput = clean(expectedOutput);
            examplePrompts = examplePrompts == null ? List.of() : examplePrompts.stream().map(Content::clean).toList();
        }

        private static String clean(String text) {
            return text == null ? "" : text.strip();
        }
    }

    public record Resource(String id, Scope scope, String profileId, State state, Long releaseVersion,
                           long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        public ResourceRef ref() {
            return new ResourceRef(scope.tenantId(), ASSISTANT, id);
        }

        public ResourceDescriptor descriptor() {
            return new ResourceDescriptor(ref(),
                    new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE, scope.tenantId(), scope.workspaceId()),
                    state.name(), version, createdBy);
        }

        public DefinitionRef profile(long publishedVersion) {
            return new DefinitionRef("chat-profile", profileId, Long.toString(publishedVersion));
        }
    }

    public record Draft(String assistantId, long revision, Content content, String updatedBy, Instant updatedAt) {
    }

    public record Release(String assistantId, long version, long draftRevision, String trialId,
                          String contentDigest, Content content, String publishedBy, Instant publishedAt) {
    }

    public record Trial(String id, String assistantId, long draftRevision, String actorId, String contentDigest,
                        String snapshotId, String invocationId) {
    }

    public record Detail(Resource resource, Draft draft) {
    }

    public record Managed(Resource resource, String displayName, long draftRevision) {
    }

    public record Summary(String assistantId, String displayName, String description, List<String> examplePrompts,
                          DefinitionRef chatProfile) {
    }

    public record Receipt(String assistantId, long resourceVersion, long draftRevision, DefinitionRef chatProfile) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
