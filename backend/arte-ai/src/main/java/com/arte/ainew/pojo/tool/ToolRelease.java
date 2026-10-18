package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 固定工具版本的当前治理状态
 * <p>
 * 独立于不可变产品定义；生命周期资格不代替当前授权或执行器就绪。
 *
 * @param tool                  固定工具版本。
 * @param state                 当前生命周期状态。
 * @param version               治理行版本；0 为尚未执行管理命令的派生草稿。
 * @param publication           首次发布的不可变契约与审核记录，草稿为 null。
 * @param resumeState           停用前的 PUBLISHED／DEPRECATED 状态；重新启用只恢复该状态。
 * @param existingBindingsUntil 废弃版本既有绑定的可用截止；停用期间保留，null 表示未另设截止。
 * @param changedAt             最近成功变更的 UTC 微秒时间；派生草稿为 null。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public record ToolRelease(DefinitionRef tool, State state, long version, Publication publication,
                          State resumeState, Instant existingBindingsUntil, Instant changedAt) {

    public enum State {DRAFT, PUBLISHED, DEPRECATED, DISABLED}

    public ToolRelease {
        Objects.requireNonNull(tool, "tool").requireType("tool");
        Objects.requireNonNull(state, "state");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        if (state == State.DRAFT) {
            ContractChecks.require(version == 0 && publication == null && resumeState == null
                    && existingBindingsUntil == null && changedAt == null, "Invalid draft release");
        } else {
            ContractChecks.require(version > 0, "Managed release requires a positive version");
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(changedAt, "changedAt");
            ContractChecks.require((state == State.DISABLED) == (resumeState != null), "Only disabled releases retain a resume state");
            if (resumeState != null)
                ContractChecks.require(resumeState == State.PUBLISHED || resumeState == State.DEPRECATED, "Invalid resume state");
            ContractChecks.require(state == State.DEPRECATED || resumeState == State.DEPRECATED || existingBindingsUntil == null,
                    "Only deprecated releases retain an existing-binding deadline");
            var predecessor = publication.review().compatibleFrom();
            ContractChecks.require(predecessor == null || predecessor.id().equals(tool.id()) && !predecessor.equals(tool), "Invalid compatibility predecessor");
        }
    }

    public static ToolRelease draft(DefinitionRef tool) {
        return new ToolRelease(tool, State.DRAFT, 0, null, null, null, null);
    }

    /**
     * 仅生命周期上的新建绑定资格；空间可见性和当前权限仍须另验。
     */
    public boolean permitsNewBinding() {
        return state == State.PUBLISHED;
    }

    /**
     * 仅生命周期上的既有绑定资格；停用不阻止查询、核对或费用收尾。
     */
    public boolean permitsExistingBinding(Instant now) {
        Objects.requireNonNull(now, "now");
        return state == State.PUBLISHED
                || state == State.DEPRECATED && (existingBindingsUntil == null || now.isBefore(existingBindingsUntil));
    }

    /**
     * 首次发布凭据；契约摘要覆盖产品、提供方、Schema 内容及执行目标。
     *
     * @param contractDigest    固定发布契约摘要，不是活动目录状态或授权摘要。
     * @param discoveryChecksum 发布检查所见节点清单摘要，不证明整个集群同构。
     * @param review            首次审核材料及不可变兼容前驱。
     * @param actorSubjectId    首次发布的真实稳定主体，不使用用户名。
     * @param publishedAt       首次发布时间；重新启用不改写。
     */
    public record Publication(String contractDigest, String discoveryChecksum, ToolReleaseCommand.Review review,
                              String actorSubjectId, Instant publishedAt) {

        public Publication {
            ContractChecks.digest(contractDigest, "contractDigest");
            ContractChecks.digest(discoveryChecksum, "discoveryChecksum");
            Objects.requireNonNull(review, "review");
            ContractChecks.id(actorSubjectId, "actorSubjectId");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }
}
