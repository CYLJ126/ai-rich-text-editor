package com.arte.ainew.api.model;

import com.arte.ainew.pojo.model.ModelUsageChecks;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * 以当前使用主体和目标执行空间准备精确模型的兼容预算。同步数据库线程调用，不访问供应商。
 * 不修改模型发布、批准额度、历史执行或聊天配置；普通用户不能自报主体、金额、费率或预算引用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
public interface ModelUsagePreparationService {

    /**
     * 当前事实查询，不创建空间 AI、预算、账户、回执或事件。
     */
    ModelUsagePreparation status(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding);

    /**
     * 受权分项诊断，未执行的检查不得推断为通过。
     */
    ModelUsageChecks checks(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding);

    /**
     * 按当前批准规则幂等准备；重放仍重查当前模型、凭据用途、范围和预算资格。
     */
    CommandReceipt<ModelUsagePreparation> prepare(TrustedActor actor, CommandMeta meta, Scope scope,
                                                  ResourceRef resource, DefinitionRef binding);
}
