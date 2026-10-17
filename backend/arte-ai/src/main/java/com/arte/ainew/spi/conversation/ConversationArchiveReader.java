package com.arte.ainew.spi.conversation;

import com.arte.ainew.common.execution.ExecutionOwner;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 归档会话读取器
 * <p>
 * 调用者仍须验证存在性、归属及可见性。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationArchiveReader {

    /**
     * 返回输入集合中已归档会话的 ID
     *
     * @param owner           执行所有者
     * @param conversationIds 会话 ID 集合
     * @return 已归档会话的 ID 集合
     */
    Mono<Set<String>> findArchivedConversationIds(ExecutionOwner owner, Set<String> conversationIds);
}
