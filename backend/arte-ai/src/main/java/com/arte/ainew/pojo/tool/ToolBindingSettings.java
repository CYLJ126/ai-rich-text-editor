package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一份完整的个人绑定配置
 * <p>
 * 更新替换整个绑定层，不与上一修订的配置合并。
 *
 * @param tool           固定基准工具；升级基准必须显式使用 UPGRADE。
 * @param versionPolicy  精确锁定或沿已审核兼容链解析。
 * @param name           当前绑定展示名，同一工具可以有多个命名绑定。
 * @param enabled        是否允许新使用；停用仍保留历史修订。
 * @param configuration  完整的非敏感绑定层配置，解析时与工具默认值递归合并。
 * @param secrets        仅固定 secret 引用；当前本地协议明确拒绝非空集合，不解析凭据值。
 * @param policy         可选收紧策略，null 表示不额外收紧，不覆盖上层限制。
 * @param networkOrigins 可选网络范围，null 继承工具范围，空集合表示禁止外发；不得新增工具未允许的目标。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingSettings(DefinitionRef tool, VersionPolicy versionPolicy, String name, boolean enabled,
                                  StructuredValue.ObjectValue configuration, Map<String, DefinitionRef> secrets,
                                  ToolExecutionPolicy policy, Set<URI> networkOrigins) {

    public enum VersionPolicy {
        /**
         * 只解析指定固定版本，不跟随后续发布。
         */
        PINNED,
        /**
         * 新解析沿显式审核链选择；旧固定材料读取不重新选版。
         */
        FOLLOW_COMPATIBLE
    }

    public ToolBindingSettings {
        Objects.requireNonNull(tool, "tool").requireType("tool");
        Objects.requireNonNull(versionPolicy, "versionPolicy");
        ContractChecks.text(name, "name", 200);
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(secrets, "secrets");
        ContractChecks.range(secrets.size(), "secrets.size", 0, 32);
        secrets = Map.copyOf(secrets);
        secrets.forEach((slot, ref) -> {
            ContractChecks.id(slot, "secret slot");
            ref.requireType("secret");
        });
        if (networkOrigins != null) {
            networkOrigins = ContractChecks.set(networkOrigins, "networkOrigins", ContractChecks.MAX_ITEMS);
            networkOrigins.forEach(ToolRiskProfile::validateOrigin);
        }
    }

    /**
     * 启停复制为新修订；不改变基准、配置、凭据引用或策略。
     */
    public ToolBindingSettings withEnabled(boolean enabled) {
        return new ToolBindingSettings(tool, versionPolicy, name, enabled, configuration, secrets, policy, networkOrigins);
    }
}
