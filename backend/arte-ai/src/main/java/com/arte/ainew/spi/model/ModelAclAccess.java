package com.arte.ainew.spi.model;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.model.ModelAccessCandidate;
import com.arte.ainew.pojo.model.ModelAccessProof;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 宿主公共模型授权桥
 * <p>
 * 缺失迁移记录才是 LEGACY，读取错误不能伪装成未迁移。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public interface ModelAclAccess {

    record Decision(boolean migrated, Map<DefinitionRef, ModelAccessProof> allowed) {

        public Decision {
            allowed = Map.copyOf(allowed);
            if (!migrated && !allowed.isEmpty()) {
                throw new IllegalArgumentException("Legacy scope cannot carry ACL proofs");
            }
            allowed.keySet().forEach(reference -> reference.requireType("binding"));
        }
    }

    Mono<Decision> check(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates);

    /**
     * 原写事务中复核迁移来源、映射、当前公共资格和内容；禁止使用展示快照。
     */
    Decision checkCurrent(ExecutionContext context, AiEntitlement.Source source, List<ModelAccessCandidate> candidates);
}
