package com.arte.ainew.pojo.context;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;

/**
 * 服务端记录的助手来源
 * <p>
 * 随 {@link ContextSnapshot} 保存，用于追踪和核对本次调用采用的助手内容。
 * 已发布的助手会话固定引用一个发布版本，草稿试用固定引用一次私有试用。两种来源必须且只能指定一种。
 *
 * @param assistantId   助手资源的唯一标识。
 * @param profile       已发布聊天配置的精确版本引用，类型为 {@code chat-profile}，草稿试用时为空。
 * @param trialId       私有草稿试用记录的唯一标识，使用发布版本时为空。
 * @param revision      本次使用的草稿修订号，必须大于零。使用发布版本时记录该版本所对应的草稿修订号。
 * @param contentDigest 本次采用的助手内容摘要，用于核对上下文快照与对应的发布或试用记录是否一致。
 * @param displayName   写入快照时的助手展示名称，用于显示本次调用关联的助手。
 * @param trialDeadline 草稿试用首次提交时固定的执行截止时间，重试时沿用该上限。使用发布版本时为空。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public record AssistantSource(String assistantId, DefinitionRef profile, String trialId, long revision,
                              String contentDigest, String displayName, Instant trialDeadline) {

    public AssistantSource {
        ContractChecks.id(assistantId, "assistantId");
        ContractChecks.require((profile == null) != (trialId == null) && revision > 0, "Invalid assistant source");
        ContractChecks.require((trialDeadline == null) == (trialId == null), "Invalid trial deadline");
        if (profile != null) profile.requireType("chat-profile");
        if (trialId != null) ContractChecks.id(trialId, "trialId");
        ContractChecks.digest(contentDigest, "contentDigest");
        ContractChecks.text(displayName, "displayName", 160);
    }
}
