package com.arte.ainew.api.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.ResolvedBinding;
import reactor.core.publisher.Mono;

/**
 * 绑定/解绑管理服务
 * <p>
 * 负责解析“当前用户可以使用的模型绑定”，绑定让业务代码通过一个固定版本的引用，找到实际执行所需的配置，随后构造相应约束。
 * 主要操作：绑定／解绑、范围配置、解析可用操作等。
 * 边界：表达使用配置，不替代资源授权。
 * <p>
 * 示例：
 * 绑定 default-text/v1
 * ├── 能力：text-generation/v1
 * ├── 连接：default-model/v1
 * ├── 远端模型：例如 deepseek-chat
 * ├── 上下文窗口：允许容纳多少 Token
 * └── 费率：使用哪个版本的计费规则
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:34 ✾
 **/
public interface BindingManager {

    /**
     * 解析精确发布版本并检查能力、使用范围与停用状态；不能以 latest 替换记录的版本。
     *
     * @param binding    用户选择的绑定及版本
     * @param capability 本次要使用的能力及版本
     * @param context    执行上下文，当前可信身份、空间、权限和执行期限
     * @return 解析后的绑定信息，包含能力描述、连接引用、远端模型名称、上下文容量和费率引用
     */
    Mono<ResolvedBinding> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context);
}
