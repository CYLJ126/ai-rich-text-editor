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

    /**
     * 助手资源的生命周期状态，用于判断发布版本是否可选用，并通过固定编码持久化。
     */
    public enum State {
        /**
         * 尚未发布，具备相应权限的成员可编辑和试用草稿。
         */
        DRAFT(0),
        /**
         * 已发布，获授权的成员可选用当前发布版本。
         */
        ACTIVE(1),
        /**
         * 已停用，保留草稿和发布历史，禁止继续执行助手调用。
         */
        DISABLED(2);

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

    /**
     * 草稿和发布版本共用的助手内容。构造时去除文本首尾空白，将缺省文本和示例分别转为空字符串和空列表。
     *
     * @param displayName       展示在管理目录、可用目录和会话中的助手名称。
     * @param description       面向使用者的助手用途说明。
     * @param systemInstruction 执行时由服务端写入系统消息的工作指令。
     * @param expectedOutput    追加到系统消息中的预期输出说明，描述结果的格式或内容要求。
     * @param examplePrompts    供使用者参考或填入输入框的示例提示词，保留原有顺序。
     */
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

    /**
     * 助手资源的身份、归属和生命周期元数据，用于权限校验、版本管理和构造发布配置引用。
     *
     * @param id             助手资源的唯一标识，用于关联草稿、试用和发布记录。
     * @param scope          助手所属的租户与工作空间。
     * @param profileId      跨发布版本保持稳定的聊天配置标识，与发布版本号共同定位精确配置。
     * @param state          助手当前的生命周期状态。
     * @param releaseVersion 最近一次发布的版本号，首次发布前为空，停用时保留。
     * @param version        资源的乐观锁版本，初始为零，用于校验管理写入是否基于当前状态。
     * @param createdBy      创建助手的主体标识。
     * @param createdAt      助手资源的创建时间。
     * @param updatedBy      最近一次修改助手资源的主体标识。
     * @param updatedAt      助手资源的最近修改时间。
     */
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

    /**
     * 助手当前的草稿，供管理端保存、试用和发布时读取并核对修订。
     *
     * @param assistantId 所属助手资源的标识。
     * @param revision    草稿修订号，从一开始，在草稿内容变更时递增。
     * @param content     当前修订保存的助手内容。
     * @param updatedBy   最近一次保存草稿的主体标识。
     * @param updatedAt   草稿当前修订的保存时间。
     */
    public record Draft(String assistantId, long revision, Content content, String updatedBy, Instant updatedAt) {
    }

    /**
     * 发布时固定的助手内容及其来源，用于版本历史和按精确版本执行助手会话。
     *
     * @param assistantId   所属助手资源的标识。
     * @param version       该助手的发布版本号，从一开始，随每次发布递增。
     * @param draftRevision 本次发布采用的草稿修订号。
     * @param trialId       通过发布校验的成功试用记录标识。
     * @param contentDigest 发布内容摘要，用于核对内容与来源记录的一致性。
     * @param content       发布时冻结的助手内容快照。
     * @param publishedBy   执行发布的主体标识。
     * @param publishedAt   该版本的发布时间。
     */
    public record Release(String assistantId, long version, long draftRevision, String trialId,
                          String contentDigest, Content content, String publishedBy, Instant publishedAt) {
    }

    /**
     * 一次私有草稿试用的持久记录，关联固定修订、输入快照和执行，用于幂等恢复和发布校验。
     *
     * @param id            试用记录的唯一标识。
     * @param assistantId   被试用的助手资源标识。
     * @param draftRevision 试用时锁定的草稿修订号。
     * @param actorId       发起试用且持有本次执行的主体标识。
     * @param contentDigest 试用时固定的草稿内容摘要，用于与待发布内容比对。
     * @param snapshotId    本次试用冻结的输入上下文快照标识。
     * @param invocationId  为本次试用预先分配的执行标识，受理后用于查询执行结果。
     */
    public record Trial(String id, String assistantId, long draftRevision, String actorId, String contentDigest,
                        String snapshotId, String invocationId) {
    }

    /**
     * 当前主体在指定工作空间和助手上的操作资格，用于管理界面展示可用操作。实际提交时由服务端复核权限和业务条件。
     *
     * @param canCreate  是否具备在当前工作空间创建助手的权限。
     * @param canEdit    是否可编辑指定助手的草稿。
     * @param canTrial   是否同时具备编辑和使用指定助手的权限，以发起草稿试用。
     * @param canPublish 是否具备发布指定助手的权限。
     * @param canDisable 是否可停用指定助手。
     */
    public record Capabilities(boolean canCreate, boolean canEdit, boolean canTrial, boolean canPublish,
                               boolean canDisable) {
    }

    /**
     * 当前主体的试用历史条目，用于浏览试用记录和定位对应的执行结果。
     *
     * @param trialId       试用记录的标识。
     * @param draftRevision 该次试用采用的草稿修订号。
     * @param invocationId  对应的执行标识，用于查询受理状态、执行结果和用量。
     * @param createdAt     试用输入快照的创建时间。
     */
    public record TrialHistory(String trialId, long draftRevision, String invocationId, Instant createdAt) {
    }

    /**
     * 助手管理详情，用于编辑当前草稿并比较它与最近一次发布采用的修订。
     *
     * @param resource               助手资源的归属、状态和版本信息。
     * @param draft                  当前保存的草稿。
     * @param publishedDraftRevision 最近一次发布采用的草稿修订号，首次发布前为空。
     */
    public record Detail(Resource resource, Draft draft, Long publishedDraftRevision) {
    }

    /**
     * 助手管理目录条目，用于展示资源状态以及当前草稿的名称和修订号。
     *
     * @param resource      助手资源的归属、状态和版本信息。
     * @param displayName   当前草稿中的助手展示名称。
     * @param draftRevision 当前草稿的修订号。
     */
    public record Managed(Resource resource, String displayName, long draftRevision) {
    }

    /**
     * 已发布助手的展示摘要，用于可用目录选用和会话中的助手信息展示。
     *
     * @param assistantId    助手资源的标识。
     * @param displayName    该发布版本的展示名称。
     * @param description    该发布版本的用途说明。
     * @param examplePrompts 该发布版本提供的示例提示词。
     * @param chatProfile    该发布版本的精确聊天配置引用，用于绑定或识别会话采用的版本。
     */
    public record Summary(String assistantId, String displayName, String description, List<String> examplePrompts,
                          DefinitionRef chatProfile) {
    }

    /**
     * 会话绑定的助手信息，用于展示历史版本以及当前主体能否继续使用该助手。
     *
     * @param summary   会话绑定的固定发布版本摘要。
     * @param available 助手是否处于已发布状态且当前主体仍具备使用资格。
     * @param disabled  助手资源是否已停用。
     */
    public record ConversationAssistant(Summary summary, boolean available, boolean disabled) {
    }

    /**
     * 助手写命令的业务回执，记录命令完成时的资源和草稿版本，供界面更新及幂等重放使用。
     *
     * @param assistantId     命令涉及的助手资源标识。
     * @param resourceVersion 命令结果对应的资源乐观锁版本。
     * @param draftRevision   命令结果对应的草稿修订号。
     * @param chatProfile     命令完成时最近一次发布的精确配置引用，首次发布前为空。
     */
    public record Receipt(String assistantId, long resourceVersion, long draftRevision, DefinitionRef chatProfile) {
    }

    /**
     * 助手目录、发布历史或试用历史的游标分页结果。
     *
     * @param <T>        当前查询返回的条目类型。
     * @param items      本页按查询顺序返回的条目，以不可变列表保存。
     * @param nextCursor 下一页的起始游标，已到末页时为空。
     */
    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
