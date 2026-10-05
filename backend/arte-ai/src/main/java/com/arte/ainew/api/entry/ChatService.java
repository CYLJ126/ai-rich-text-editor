package com.arte.ainew.api.entry;

import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.entry.EntryRequests;
import reactor.core.publisher.Mono;

/**
 * 聊天入口
 * <p>
 * 调用 AI 相关流程，完成聊天逻辑：提交轮次、追问、重新生成、关联动作继续交流等。
 * 边界：选择上下文与执行方式，不默认重复已完成的写操作。
 * <p>相同幂等请求返回原 Invocation；主动重新生成保留 Turn 并创建新 Invocation，
 * 自动重试仅增加 Attempt；编辑重发创建新 Turn，不修改已经执行的用户输入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 16:51 ✾
 **/
public interface ChatService {
    /**
     * 新建轮次／追问／编辑重发；核对会话版本、历史与角色后组装上下文，通过 Coordinator 可靠受理。
     */
    Mono<AcceptedExecution> submit(EntryRequests.Chat request, ExecutionContext context);

    /**
     * 校验原调用及会话归属，复用固定 Turn；重建／重新授权上下文，新建 Invocation 并关联原调用。
     */
    Mono<AcceptedExecution> regenerate(EntryRequests.Regenerate request, ExecutionContext context);
}
