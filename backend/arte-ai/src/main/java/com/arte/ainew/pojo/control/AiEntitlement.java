package com.arte.ainew.pojo.control;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.util.Objects;
import java.util.Set;

/**
 * 本次模型使用资格的业务视图
 * <p>
 * 不持久化，不授予其他模型、预算、凭据或公共资源 ACL 权限。
 *
 * @param executionOwner 获准使用模型的原执行归属，包含租户、空间和稳定主体；不表示模型资源所有者
 * @param binding        已核对资格的精确版本模型绑定，保留能力、连接、模型操作和费率引用
 * @param connection     与绑定一致的连接定义，仅包含凭据引用，不包含凭据明文
 * @param releaseRef     同次配置的发布标识，不用最新版本替换原执行的固定发布依赖
 * @param grantRef       当前资格依据标识；DATABASE 为当前空间配置版本依据，不等同于完整公共 ACL 版本
 * @param source         本次资格的受信来源；不从固定和数据库来源合并允许项
 * @param allowedActions 本次允许的动作，当前仅提供 model:use，不包含模型管理或凭据读取资格
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public record AiEntitlement(ExecutionOwner executionOwner, ResolvedBinding binding, ConnectionDefinition connection,
                            String releaseRef, String grantRef, Source source, Set<Action> allowedActions) {

    public enum Source {FIXED, DATABASE}

    public AiEntitlement {
        Objects.requireNonNull(executionOwner);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(connection);
        ContractChecks.require(binding.connection().equals(connection.definition()), "Entitlement connection mismatch");
        ContractChecks.id(releaseRef, "releaseRef");
        ContractChecks.id(grantRef, "grantRef");
        Objects.requireNonNull(source);
        allowedActions = Set.copyOf(allowedActions);
        ContractChecks.require(!allowedActions.isEmpty(), "Missing entitlement action");
    }
}
