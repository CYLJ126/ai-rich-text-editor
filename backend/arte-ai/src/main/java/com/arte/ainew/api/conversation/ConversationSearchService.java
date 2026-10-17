package com.arte.ainew.api.conversation;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.search.ConversationSearch;
import reactor.core.publisher.Mono;

/**
 * 会话搜索服务
 * <p>
 * 实现和 HTTP 入口在搜索后端阶段交付，每次重新授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchService {

    /**
     * 校验当前权限，首次查询创建搜索会话和快照，后续按游标复用快照分页。
     * 过滤当前不可见的命中，返回展示片段、高亮及翻页游标。
     */
    Mono<ConversationSearch.Page> search(ConversationSearch.Query query, ExecutionContext context);

    /**
     * 校验当前权限，关闭当前主体的搜索会话并释放活动名额，异步清理关联的搜索快照。
     */
    Mono<Void> close(String searchSessionId, ExecutionContext context);

    /**
     * 校验当前权限、会话可见性及版本，读取目标轮次附近或历史游标指定的聊天历史。
     * 返回会话版本、轮次记录及前后浏览游标，不依赖原搜索会话仍有效。
     */
    Mono<ConversationSearch.AroundPage> queryTurnsAround(ConversationSearch.AroundQuery query, ExecutionContext context);
}
