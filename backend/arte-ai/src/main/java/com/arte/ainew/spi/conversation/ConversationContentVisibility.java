package com.arte.ainew.spi.conversation;

import com.arte.ainew.pojo.execution.Invocation;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * 会话内容可读性检查接口
 * <p>
 * 现有候选正文读取复用当前会话／归档资格，不依赖搜索 PIT 是否活动。
 * 读取某次模型调用的回答前，确认当前用户现在是否还能查看这条回答。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@FunctionalInterface
public interface ConversationContentVisibility {

    /**
     * 输入当前用户归属和待检查的调用记录，返回允许读取的调用 ID 集合，不返回正文。
     *
     * @param owner       当前用户归属
     * @param invocations 待检查的调用记录
     * @return 允许读取的调用 ID 集合
     */
    Mono<Set<String>> readableInvocations(ExecutionOwner owner, Map<String, Invocation> invocations);
}
