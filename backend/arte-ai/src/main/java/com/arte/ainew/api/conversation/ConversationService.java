package com.arte.ainew.api.conversation;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.core.pojo.PageParam;
import reactor.core.publisher.Mono;

import java.util.List;

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

    /**
     * 服务端分配会话 ID／归属；需幂等键，资料关联须校验但不授予后续读取权限，profile 可不选择。
     */
    Mono<Conversation> create(String title, DefinitionRef chatProfile, List<ResourceRef> resources, ExecutionContext context);

    /**
     * 当前授权下查询会话；不存在／不可见明确失败，不返回 null。
     */
    Mono<Conversation> find(String conversationId, ExecutionContext context);

    /**
     * 当前 owner 的会话，数据库分页并按会话键稳定排序；不修改请求分页对象。
     */
    Mono<ConversationPage<Conversation>> list(PageParam page, ExecutionContext context);

    /**
     * 展示历史轮次，按 sequence 升序；固定会话版本，和生成时的 history 选择独立。
     */
    Mono<ConversationPage<Turn>> turns(String conversationId, long expectedVersion, PageParam page, ExecutionContext context);

    /**
     * 校验 Turn 属于目标会话及当前主体；不复制 Invocation 执行状态。
     */
    Mono<Turn> turn(String conversationId, String turnId, ExecutionContext context);

    /**
     * 按固定版本和历史路径选取有界轮次；版本冲突明确拒绝，不自动读取之后新增的历史。
     */
    Mono<List<Turn>> history(ContextRequest.HistorySelection selection, ExecutionContext context);
}
