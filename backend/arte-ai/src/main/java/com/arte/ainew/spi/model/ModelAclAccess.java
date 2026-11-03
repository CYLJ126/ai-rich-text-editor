package com.arte.ainew.spi.model;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelAccessCandidate;
import com.arte.ainew.pojo.model.ModelAccessProof;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主公共模型授权桥
 * <p>
 * 旧目录缺失迁移记录才是 LEGACY；个人保留绑定始终按独立证明判定，读取错误不能伪装成兼容授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public interface ModelAclAccess {

    /**
     * 同次候选的公共授权结果。
     *
     * @param migrated         是否已整体迁移旧目录
     * @param allowed          通过公共授权的精确绑定证明
     * @param resourceBindings 个人保护引用，即使被拒绝也不能退回旧 Grant
     */
    record Decision(boolean migrated, Map<DefinitionRef, ModelAccessProof> allowed,
                    Set<DefinitionRef> resourceBindings) {

        public Decision(boolean migrated, Map<DefinitionRef, ModelAccessProof> allowed) {
            this(migrated, allowed, Set.of());
        }

        public Decision {
            allowed = Map.copyOf(allowed);
            resourceBindings = Set.copyOf(resourceBindings);
            if (!migrated && !resourceBindings.containsAll(allowed.keySet())) {
                throw new IllegalArgumentException("Legacy scope cannot carry ACL proofs");
            }
            allowed.keySet().forEach(reference -> reference.requireType("binding"));
        }
    }

    /**
     * 读取本次候选的当前资格，不将拒绝或读取错误解释为旧 Grant 允许。
     *
     * @param context    原可信执行上下文
     * @param source     同次运行配置的唯一来源
     * @param candidates 同次配置中的精确候选
     * @return 异步资格及受保护引用，不授予预算或凭据明文读取权
     */
    Mono<Decision> check(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates);

    /**
     * 原写事务中复核迁移来源、映射、当前公共资格和内容；禁止使用展示快照。
     *
     * @param context    原可信执行上下文
     * @param source     同次运行配置的唯一来源
     * @param candidates 待提交或派发的精确候选
     * @return 原事务内的当前资格，失败时阻止继续写入
     */
    Decision checkCurrent(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates);
}
