package com.arte.ainew.api.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.ResolvedBinding;
import reactor.core.publisher.Mono;

/**
 * 绑定/解绑管理服务
 * <p>
 * 主要操作：绑定／解绑、范围配置、解析可用操作等。
 * 边界：表达使用配置，不替代资源授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:34 ✾
 **/
public interface BindingManager {

    /** 解析精确发布版本并检查能力、使用范围与停用状态；不能以 latest 替换记录的版本。 */
    Mono<ResolvedBinding> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context);
}
