package com.arte.ainew.api.entry;

import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.entry.EntryRequests;
import reactor.core.publisher.Mono;

/**
 * AI 动作入口
 * <p>
 * 调用 AI 相关流程，完成相关逻辑：执行已发布动作、查询结果、转为追问等。
 * 边界：无需创建会话；文章操作是注册场景，非核心必需依赖。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 16:51 ✾
 **/
public interface AiActionService {
    /**
     * 解析已发布动作与类型化输入，选择上下文及能力，通过 Coordinator 受理；不直接改业务资源。
     */
    Mono<AcceptedExecution> execute(EntryRequests.Action request, ExecutionContext context);
}
