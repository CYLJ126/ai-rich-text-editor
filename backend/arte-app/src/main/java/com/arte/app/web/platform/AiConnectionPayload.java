package com.arte.app.web.platform;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.app.platform.ai.AiControlData.CatalogView;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 页面管理连接的公开视图，不返回平台凭据引用，更不返回密钥。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 13:50 ✾
 */
public final class AiConnectionPayload {

    private AiConnectionPayload() {
    }

    public record Connection(DefinitionRef reference, String providerId, DefinitionRef protocol, String endpoint,
                             String state, long connectTimeoutSeconds, long responseTimeoutSeconds,
                             int maxResponseBytes) {
    }

    public record View(long version, boolean initialized, List<Connection> connections, List<String> allowedOrigins) {
        public static View from(CatalogView view, List<String> origins) {
            var catalog = view.catalog();
            return new View(view.version(), catalog != null, catalog == null ? List.of() : catalog.connections().stream()
                    .map(c -> new Connection(c.definition(), c.providerId(), c.protocol(), c.endpoint().toASCIIString(),
                            catalog.disabledConnections().contains(c.definition().id()) ? ConnectionDefinition.State.DISABLED.name() : c.state().name(),
                            c.connectTimeout().toSeconds(), c.responseTimeout().toSeconds(), c.maxResponseBytes())).toList(), origins);
        }

        public static CommandReceipt<View> receipt(CommandReceipt<CatalogView> value, List<String> origins) {
            return new CommandReceipt<>(value.operationId(), value.status(), value.delivery(), from(value.value(), origins),
                    value.resultVersion(), value.acceptedAt(), value.completedAt());
        }
    }
}
