package com.arte.ainew.application.model;

import com.arte.ainew.infrastructure.control.ControlDefinitionChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.model.ReviewedModelPlan;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * 模型审核方案检查
 * <p>
 * 将审核规格适配为网关兼容性检查的本地元数据；不持久化或发布执行定义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 12:50 ✾
 */
public final class ReviewedModelPlanChecks {

    private ReviewedModelPlanChecks() {
    }

    /**
     * 返回仅供本地能力判断的临时结构，不作为实际模型绑定或授权使用。
     */
    public static ResolvedBinding metadata(ReviewedModelPlan plan) {
        CapabilityDescriptor capability = new CapabilityDescriptor(new DefinitionRef("capability", "plan-check-" + plan.planRef().id(), plan.planRef().version()),
                CapabilityDescriptor.Kind.GENERATION, ControlDefinitionChecks.INPUT_SCHEMA, ControlDefinitionChecks.OUTPUT_SCHEMA,
                plan.features(), CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
        return new ResolvedBinding(new DefinitionRef("binding", "plan-check-" + plan.planRef().id(), plan.planRef().version()),
                capability, plan.connection(), plan.remoteModel(), plan.contextWindowTokens(), plan.rate());
    }
}
