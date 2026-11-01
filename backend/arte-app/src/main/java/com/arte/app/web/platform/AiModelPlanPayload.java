package com.arte.app.web.platform;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
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

    public record Plan(DefinitionRef binding, DefinitionRef connection, DefinitionRef rate, String remoteModel,
                       long contextWindowTokens, String currency, String inputPerMillion, String outputPerMillion,
                       boolean usageReporting) {
    }

    public record View(long version, boolean initialized, List<Plan> plans) {
        public static View from(CatalogView view) {
            if (view.catalog() == null) {
                return new View(view.version(), false, List.of());
            }
            List<Plan> plans = view.catalog().bindings().stream()
                    .filter(binding -> binding.capability().kind() == CapabilityDescriptor.Kind.GENERATION)
                    .map(binding -> plan(binding, view.catalog().rates().stream().filter(rate -> rate.definition().equals(binding.rate()))
                            .findFirst().orElseThrow())).toList();
            return new View(view.version(), true, plans);
        }

        private static Plan plan(ResolvedBinding binding, NewAiProperties.Rate rate) {
            return new Plan(binding.definition(), binding.connection(), rate.definition(), binding.remoteOperation(),
                    binding.contextWindowTokens(), rate.currency().getCurrencyCode(), rate.inputPerMillion().amount().toPlainString(),
                    rate.outputPerMillion().amount().toPlainString(), binding.capability().features().contains(CapabilityDescriptor.Feature.USAGE_REPORTING));
        }

        public static CommandReceipt<View> receipt(CommandReceipt<CatalogView> receipt) {
            return new CommandReceipt<>(receipt.operationId(), receipt.status(), receipt.delivery(), from(receipt.value()),
                    receipt.resultVersion(), receipt.acceptedAt(), receipt.completedAt());
        }
    }
}
