package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 空间显式启用公共模型 ACL 的持久化清单；记录存在即禁止旧 Grant 兜底。
 *
 * @param scope               明确切换的执行租户与空间，不改变历史执行归属
 * @param configurationSource 该范围唯一接受的运行配置来源，不能隐式切换
 * @param bindings            明确允许进入 ACL 判断的精确绑定集合；空集合表示全部拒绝
 * @param version             清单 CAS 版本，首次为 1，后续逐次递增
 * @param changedBy           本次变更的稳定主体 ID
 * @param changedAt           本次成功变更的服务端时间
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public record ModelAclScope(Scope scope, AiEntitlement.Source configurationSource, Set<DefinitionRef> bindings,
                            long version, String changedBy, Instant changedAt) {

    public ModelAclScope {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(configurationSource);
        Objects.requireNonNull(changedAt);
        bindings = Set.copyOf(bindings);
        bindings.forEach(reference -> reference.requireType("binding"));
        PlatformChecks.require(version > 0 && bindings.size() <= 256, "Invalid model ACL scope");
        PlatformChecks.id(changedBy, "changedBy");
    }
}
