package com.arte.app.web.platform;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.model.ReviewedModelPlan;
import com.arte.app.platform.ai.AiControlData.CatalogView;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 审核模型及十进制费率的安全视图，不暴露连接凭据或旧授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 14:45 ✾
 */
public final class AiModelPlanPayload {

    private AiModelPlanPayload() {
    }

    public record Plan(DefinitionRef planRef, String displayName, DefinitionRef connection, DefinitionRef rate,
                       String remoteModel,
                       long contextWindowTokens, String currency, String inputPerMillion, String outputPerMillion,
                       boolean usageReporting, boolean toolCalls, java.time.Instant publishedAt, String publishedBy,
                       long catalogRevision) {
    }

    public record View(long version, boolean initialized, List<Plan> plans) {
        public static View from(CatalogView view) {
            if (view.catalog() == null) {
                return new View(view.version(), false, List.of());
            }
            List<Plan> plans = view.catalog().reviewedPlans().stream()
                    .map(plan -> plan(plan, view.catalog().rates().stream().filter(rate -> rate.definition().equals(plan.rate()))
                            .findFirst().orElseThrow())).toList();
            return new View(view.version(), true, plans);
        }

        private static Plan plan(ReviewedModelPlan plan, ControlConfiguration.Rate rate) {
            return new Plan(plan.planRef(), plan.displayName(), plan.connection(), plan.rate(), plan.remoteModel(),
                    plan.contextWindowTokens(), rate.currency().getCurrencyCode(), rate.inputPerMillion().amount().toPlainString(),
                    rate.outputPerMillion().amount().toPlainString(), plan.features().contains(CapabilityDescriptor.Feature.USAGE_REPORTING),
                    plan.features().contains(CapabilityDescriptor.Feature.TOOL_CALLS), plan.publishedAt(), plan.publishedBy(), plan.catalogRevision());
        }

        public static CommandReceipt<View> receipt(CommandReceipt<CatalogView> receipt) {
            return new CommandReceipt<>(receipt.operationId(), receipt.status(), receipt.delivery(), from(receipt.value()),
                    receipt.resultVersion(), receipt.acceptedAt(), receipt.completedAt());
        }
    }
}
