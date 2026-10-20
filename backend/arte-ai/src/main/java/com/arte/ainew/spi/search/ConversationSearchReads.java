package com.arte.ainew.spi.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话搜索的权威数据读取接口
 * <p>
 * 负责回查命中的当前可见性，并读取消息定位所需的聊天历史。
 * 依据当前归属、会话及归档状态校验，不等待 ES 索引同步，不改变既有业务写入边界。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchReads {

    /**
     * 通过当前可见性校验的命中信息，包含会话标题、消息定位标识及片段回退文本。
     */
    record Visible(String title, String turnId, String messageId, String invocationId, Integer candidateOrdinal,
                   boolean partial, String fallbackText, AnswerCandidate candidate) {

        public Visible(String title, String turn, String message, String invocation, Integer ordinal, boolean partial, String fallback) {
            this(title, turn, message, invocation, ordinal, partial, fallback,
                    invocation == null ? null : AnswerCandidate.invocation(invocation));
        }
    }

    /**
     * 聊天历史读取条件，指定会话版本、目标轮次及翻页方向和边界。
     */
    record Around(String conversationId, String turnId, long version, int size, boolean backward, Long afterSequence) {
    }

    /**
     * 聊天历史窗口，包含会话版本、轮次记录及是否还有更早或更晚的历史。
     */
    record Window(long version, List<Turn> turns, boolean hasEarlier, boolean hasLater) {
    }

    /**
     * 批量回查 ES 命中的当前可见性，返回以 entryKey 为键的有效记录及展示、定位信息。
     */
    Map<String, Visible> visible(ExecutionOwner owner, List<ConversationSearchIndex.Hit> hits);

    /**
     * 校验会话归属、有效及未归档状态，并检查版本是否一致；不满足时抛出异常。
     */
    void requireConversation(ExecutionOwner owner, String conversationId, long version);

    /**
     * 校验会话可见性及版本后，读取目标轮次附近或指定翻页方向的聊天历史窗口。
     */
    Window around(ExecutionOwner owner, Around request);

    /**
     * 批量检查调用归属及所属会话、轮次的当前可见性，返回允许读取的调用 ID 集合。
     */
    Set<String> readableInvocations(ExecutionOwner owner, Map<String, Invocation> invocations);
}
