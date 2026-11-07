package com.arte.ainew.spi.assistant;

import com.arte.ainew.pojo.assistant.Assistants.Trial;
import com.arte.core.pojo.platform.Scope;

/**
 * 助手发布的试用校验接口
 * <p>
 * 在发布命令的原写事务中核对试用对应的执行结果和输入快照。
 * 将助手控制面的发布流程与执行结果查询对接，确保发布依据来自已成功完成的原始试用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:09 ✾
 */
@FunctionalInterface
public interface AssistantTrialVerifier {

    /**
     * 确认原始试用执行已成功，且执行归属、快照引用、助手标识、草稿修订和内容摘要与试用记录一致。
     * 调用方须提供当前可写事务，并预先核对待发布草稿与试用记录的修订号及内容摘要。校验失败时抛出异常。
     *
     * @param scope 助手所属的租户和工作空间，与试用发起人共同确定执行归属。
     * @param trial 待校验的试用记录，提供发起人、执行标识、输入快照标识和草稿来源。
     */
    void requireSucceeded(Scope scope, Trial trial);
}
