package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变模型审核方案
 * <p>
 * 只作为实际模型的发布依据，不是运行时模型或使用授权。
 *
 * @param planRef             方案族的精确版本，类型为 model-plan
 * @param displayName         审核展示名称
 * @param connection          已审核的精确连接版本
 * @param remoteModel         供应商远端模型标识
 * @param features            明确审核的文本、流式及可选用量／工具能力
 * @param contextWindowTokens 审核容量上限
 * @param rate                固定费率；不随无关方案修改重复分配
 * @param publishedAt         发布时间
 * @param publishedBy         受信审核主体
 * @param catalogRevision     发布时的目录修订，独立于方案版本
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 12:50 ✾
 */
public record ReviewedModelPlan(DefinitionRef planRef, String displayName, DefinitionRef connection,
                                String remoteModel, Set<CapabilityDescriptor.Feature> features,
                                long contextWindowTokens, DefinitionRef rate, Instant publishedAt,
                                String publishedBy, long catalogRevision) {

    public ReviewedModelPlan {
        fixed(planRef, "model-plan");
        fixed(connection, "connection");
        fixed(rate, "rate");
        PlatformChecks.id(displayName, "displayName");
        PlatformChecks.id(remoteModel, "remoteModel");
        PlatformChecks.require(remoteModel.length() <= 256 && remoteModel.chars().noneMatch(Character::isISOControl), "Invalid remote model");
        features = Set.copyOf(features);
        Set<CapabilityDescriptor.Feature> supported = Set.of(CapabilityDescriptor.Feature.TEXT_INPUT,
                CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.USAGE_REPORTING, CapabilityDescriptor.Feature.TOOL_CALLS);
        PlatformChecks.require(features.containsAll(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING))
                && supported.containsAll(features), "Unsupported reviewed features");
        PlatformChecks.require(contextWindowTokens >= 2 && contextWindowTokens <= 10_000_000 && catalogRevision > 0, "Invalid reviewed plan bounds");
        Objects.requireNonNull(publishedAt);
        PlatformChecks.id(publishedBy, "publishedBy");
    }

    private static void fixed(DefinitionRef reference, String type) {
        Objects.requireNonNull(reference).requireType(type);
        PlatformChecks.require(!reference.version().equalsIgnoreCase("latest"), "Fixed reviewed reference required");
    }

}
