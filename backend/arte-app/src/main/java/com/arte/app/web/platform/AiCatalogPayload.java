package com.arte.app.web.platform;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.app.platform.ai.AiControlData;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.reference.DefinitionRef;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * 模型目录的 HTTP 契约；保持原六字段绑定格式，不暴露内部多态执行目标或改变持久化编码。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 09:51 ✾
 */
public record AiCatalogPayload(List<CapabilityDescriptor> capabilities, List<ModelBinding> bindings,
                               List<ConnectionDefinition> connections, List<NewAiProperties.Rate> rates,
                               List<NewAiProperties.Grant> legacyGrants, List<NewAiProperties.Budget> legacyBudgets,
                               AiControlData.Preset defaults) {

    public AiCatalogPayload {
        capabilities = List.copyOf(capabilities);
        bindings = List.copyOf(bindings);
        connections = List.copyOf(connections);
        rates = List.copyOf(rates);
        legacyGrants = List.copyOf(legacyGrants);
        legacyBudgets = List.copyOf(legacyBudgets);
        Objects.requireNonNull(defaults);
    }

    public static AiCatalogPayload from(AiControlData.Catalog catalog) {
        return new AiCatalogPayload(catalog.capabilities(), catalog.bindings().stream().map(ModelBinding::from).toList(),
                catalog.connections(), catalog.rates(), catalog.legacyGrants(), catalog.legacyBudgets(), catalog.defaults());
    }

    public AiControlData.Catalog toCatalog() {
        return new AiControlData.Catalog(capabilities, bindings.stream().map(ModelBinding::toBinding).toList(),
                connections, rates, legacyGrants, legacyBudgets, defaults);
    }

    public record View(long version, AiCatalogPayload catalog) {
        public static View from(AiControlData.CatalogView value) {
            return new View(value.version(), value.catalog() == null ? null : AiCatalogPayload.from(value.catalog()));
        }

        /**
         * 回执仅转换公开值，保留原操作身份、版本及完成时间。
         */
        public static CommandReceipt<View> receipt(CommandReceipt<AiControlData.CatalogView> value) {
            return new CommandReceipt<>(value.operationId(), value.status(), value.delivery(), from(value.value()),
                    value.resultVersion(), value.acceptedAt(), value.completedAt());
        }
    }

    public record ModelBinding(DefinitionRef definition, CapabilityDescriptor capability, DefinitionRef connection,
                               String remoteOperation, Long contextWindowTokens, DefinitionRef rate) {
        static ModelBinding from(ResolvedBinding value) {
            return new ModelBinding(value.definition(), value.capability(), value.connection(), value.remoteOperation(), value.contextWindowTokens(), value.rate());
        }

        ResolvedBinding toBinding() {
            return new ResolvedBinding(definition, capability, connection, remoteOperation, contextWindowTokens, rate);
        }

        /**
         * 兼容旧页面已暂存的远端 target 形状，原键重试时不必清除或重建发布意图。
         */
        @JsonCreator
        public static ModelBinding read(@JsonProperty("definition") DefinitionRef definition,
                                        @JsonProperty("capability") CapabilityDescriptor capability,
                                        @JsonProperty("connection") DefinitionRef connection,
                                        @JsonProperty("remoteOperation") String operation,
                                        @JsonProperty("contextWindowTokens") Long window,
                                        @JsonProperty("rate") DefinitionRef rate,
                                        @JsonProperty("target") RemoteTarget target) {
            if (target != null) {
                if (connection != null && !connection.equals(target.connection())
                        || operation != null && !operation.equals(target.operation()))
                    throw new IllegalArgumentException("Conflicting model target");
                connection = target.connection();
                operation = target.operation();
            }
            return from(new ResolvedBinding(definition, capability, connection, operation, window, rate));
        }
    }

    /**
     * 只接收历史远端引用，不接受本地适配器、Java 类型名或任意多态目标。
     */
    public record RemoteTarget(String kind, DefinitionRef connection, String operation) {
        public RemoteTarget {
            if (kind != null && !kind.equals("REMOTE"))
                throw new IllegalArgumentException("Only remote model targets are supported");
            Objects.requireNonNull(connection).requireType("connection");
            Objects.requireNonNull(operation);
        }
    }
}
