package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 工具生命周期的内部管理命令
 * <p>
 * 操作者由受信入口另行提供，不能由请求体自报。
 *
 * @param tool                  固定工具版本，不按版本标签排序。
 * @param action                发布、废弃、停用或重新启用。
 * @param expectedVersion       当前治理行版本；尚无治理记录的草稿为 0。
 * @param idempotencyKey        操作者范围内的管理命令键，跨工具和动作共用键空间。
 * @param reason                必填变更原因，只保存审计，不写日志。
 * @param review                仅首次 PUBLISH 提供；重新启用重验首次发布的审核材料。
 * @param existingBindingsUntil 仅 DEPRECATE 可提供的既有绑定截止时间；null 表示未另设截止。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public record ToolReleaseCommand(DefinitionRef tool, Action action, long expectedVersion, String idempotencyKey,
                                 String reason, Review review, Instant existingBindingsUntil) {
    /**
     * 单个审核示例输入或输出最多 32 KiB 紧凑 JSON UTF-8，且仍受工具更低的策略上限限制。
     */
    public static final long MAX_EXAMPLE_BYTES = 32 * 1024;

    /**
     * 覆盖四种操作：发布、废弃、停用、重新启用
     */
    public enum Action {PUBLISH, DEPRECATE, DISABLE, REENABLE}

    public ToolReleaseCommand {
        Objects.requireNonNull(tool, "tool").requireType("tool");
        Objects.requireNonNull(action, "action");
        ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        ContractChecks.text(reason, "reason", 1024);
        ContractChecks.require((action == Action.PUBLISH) == (review != null), "Only initial publication requires a review");
        ContractChecks.require(action == Action.DEPRECATE || existingBindingsUntil == null, "Only deprecation accepts a deadline");
        if (review != null && review.compatibleFrom() != null)
            ContractChecks.require(review.compatibleFrom().id().equals(tool.id()) && !review.compatibleFrom().equals(tool),
                    "Compatibility predecessor must be another version of the same tool");
    }

    /**
     * 非敏感的正向契约示例；只校验数据，不实际执行工具来生成输出。
     *
     * @param input  符合输入 Schema 的示例参数。
     * @param output 符合输出 Schema 的预期结果。
     */
    public record Example(StructuredValue.ObjectValue input, StructuredValue output) {

        public Example {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * 人工审核材料，不把发现注解或试调用成功当作风险／语义兼容证明。
     *
     * @param examples              至少一个、最多八个非敏感输入输出示例。
     * @param riskEvidence          风险、副作用及必要权限的审核依据或受管证据引用。
     * @param idempotencyEvidence   声明领域／远端操作键防重时必填，由审核者确认实现依据。
     * @param minimumApproval       审核要求的最低人工控制，工具默认策略不得弱于它。
     * @param compatibleFrom        可选的显式兼容前驱，不表达 latest；发布后不可改变。
     * @param compatibilityEvidence 声明兼容前驱时必填的业务语义审核依据。
     */
    public record Review(List<Example> examples, String riskEvidence, String idempotencyEvidence,
                         ToolExecutionPolicy.ApprovalMode minimumApproval, DefinitionRef compatibleFrom,
                         String compatibilityEvidence) {

        public Review {
            examples = ContractChecks.list(examples, "examples", 1, 8);
            ContractChecks.text(riskEvidence, "riskEvidence", 2048);
            if (idempotencyEvidence != null) ContractChecks.text(idempotencyEvidence, "idempotencyEvidence", 2048);
            Objects.requireNonNull(minimumApproval, "minimumApproval");
            if (compatibleFrom != null) {
                compatibleFrom.requireType("tool");
                ContractChecks.text(compatibilityEvidence, "compatibilityEvidence", 2048);
            } else
                ContractChecks.require(compatibilityEvidence == null, "Compatibility evidence requires a predecessor");
        }
    }
}
