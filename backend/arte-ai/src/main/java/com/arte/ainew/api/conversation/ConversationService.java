package com.arte.ainew.api.conversation;

/**
 * 会话服务
 * <p>
 * 创建／查询／命名／删除会话，关联资料，管理历史等。
 * 边界：资料关联使用通用引用，不自动授予资源访问权。
 * <p>Conversation 与 Turn 管理归属、版本、历史路径和回答候选；引用 Invocation 查询执行状态，
 * 不维护第二份终态权威。普通轮次按会话版本受理，候选引用与当前主体归属须校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:25 ✾
 **/
public interface ConversationService {
}
