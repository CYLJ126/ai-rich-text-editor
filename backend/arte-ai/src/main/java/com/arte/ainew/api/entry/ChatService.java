package com.arte.ainew.api.entry;

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
}
