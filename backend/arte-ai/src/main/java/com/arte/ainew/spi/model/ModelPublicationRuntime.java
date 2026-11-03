package com.arte.ainew.spi.model;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.model.ModelAccessCandidate;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;

import java.util.List;

/**
 * 资源发布的配置及公共资格适配
 * <p>
 * 不并入旧 Grant，不扩大原执行或预算归属。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public interface ModelPublicationRuntime {

    /**
     * 将同空间不可变个人定义并入已授权的同次配置，精确引用冲突必须拒绝。
     *
     * @param actor   当前可信主体
     * @param scope   已核验的执行范围
     * @param base    原配置及预算，不能被实际模型覆盖
     * @param current 是否在原写事务中读取当前事实
     * @return 同一发布标识下的完整配置，不含密钥明文，不改变原 Grant 和预算
     */
    NewAiProperties contribute(TrustedActor actor, Scope scope, NewAiProperties base, boolean current);

    /**
     * 独立检查个人候选的资源、精确内容、凭据用途和当前授权，不依赖旧空间迁移清单。
     *
     * @param context    原执行上下文
     * @param candidates 同次可信目录候选
     * @return 个人受保护引用及允许证明，拒绝项不得退回旧 Grant
     */
    ModelAclAccess.Decision check(ExecutionContext context, List<ModelAccessCandidate> candidates);
}
