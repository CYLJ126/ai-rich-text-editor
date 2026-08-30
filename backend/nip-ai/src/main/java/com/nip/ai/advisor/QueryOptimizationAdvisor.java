package com.nip.ai.advisor;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nip.ai.api.MessageService;
import com.nip.ai.pojo.chat.ChatRequestDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.core.exception.ChatException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TODO 提示模板确认是否需要调整
 * 查询压缩、优化 Advisor
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/10 18:03 ✾
 **/
@Slf4j
@NullMarked
public class QueryOptimizationAdvisor extends AbstractAdvisor {

    private final ChatClient.Builder optimizationClientBuilder;
    private final MessageService messageService;
    private int order = 2;

    public QueryOptimizationAdvisor(ChatClient.Builder optimizationClientBuilder, MessageService messageService) {
        this.optimizationClientBuilder = optimizationClientBuilder;
        this.messageService = messageService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String original = req.prompt().getUserMessage().getText();
        // 如果查询重写开关关闭，则不进行查询优化
        ChatRequestDto chatRequestDto = getChatRequestDto(req);
        if (!Boolean.TRUE.equals(chatRequestDto.getQueryRewriteFlag())) {
            putIntoRequest(false, req, ORIGINAL_QUERY, original);
            putIntoRequest(false, req, FINAL_QUERY, original);
            return req;
        }

        List<Message> messages = req.prompt().getInstructions();
        // 1. 压缩会话历史
        CompressionQueryTransformer compressor = new CompressionQueryTransformer(optimizationClientBuilder, null);
        assert original != null;
        Query compressed = compressor.transform(new Query(original, messages, Map.of()));
        // 2. 重写
        RewriteQueryTransformer rewriter = new RewriteQueryTransformer(optimizationClientBuilder, null, null);
        Query rewritten = rewriter.transform(new Query(compressed.text(), messages, Map.of()));
        String finalQuery = rewritten.text();
        log.info("原查询: {}，重写后查询: {}", original, finalQuery);
        // 更新数据库
        updateMessageInfo(chatRequestDto.getUserMessageId(), finalQuery);
        // 3. 把 finalQuery 塞进 context
        Map<String, Object> ctx = putIntoRequest(
                true, req, FINAL_QUERY, finalQuery, ORIGINAL_QUERY, original);
        // 4. 替换最后一条 UserMessage 为重写后的查询
        List<Message> newMessages = replaceLastUserMessage(messages, finalQuery);

        // 不能用 chatClientRequest.prompt().mutate()，会报空指针异常，估计是框架 bug
        Prompt newPrompt = new Prompt(newMessages, req.prompt().getOptions());
        return ChatClientRequest.builder()
                .prompt(newPrompt)
                .context(ctx)
                .build();
    }

    private void updateMessageInfo(String userMessageId, String finalQuery) {
        UpdateWrapper<MessageDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(MessageDto.COL_MESSAGE_ID, userMessageId)
                .set(MessageDto.COL_OPTIMIZED_CONTENT, finalQuery);
        boolean update = messageService.update(updateWrapper);
        if (!update) {
            throw new ChatException(String.format("消息 ID ：%s，更新重写后查询失败", userMessageId));
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    public QueryOptimizationAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * 替换 messages 中最后一条 UserMessage 的文本，保留其媒体内容
     *
     * @param messages   原始消息列表（不可变）
     * @param finalQuery 重写后的查询文本
     * @return 新消息列表
     */
    private List<Message> replaceLastUserMessage(List<Message> messages, String finalQuery) {
        // 从后往前找最后一条 UserMessage 的索引
        int lastIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastIdx = i;
                break;
            }
        }
        // 未找到（理论上不会发生），原样返回
        if (lastIdx < 0) {
            log.warn("未找到 UserMessage，跳过替换");
            return new ArrayList<>(messages);
        }
        UserMessage original = (UserMessage) messages.get(lastIdx);
        // 保留多模态媒体内容，只替换文本
        UserMessage replaced = StrUtil.isNotBlank(original.getText())
                ? UserMessage.builder()
                .text(finalQuery)
                .media(original.getMedia())       // 保留图片/音频等
                .metadata(original.getMetadata()) // 保留元数据
                .build()
                : original; // 纯媒体消息不替换
        // 构建新列表：前段 + 替换后的消息 + 后段（lastIdx 是最后一条，后段为空）
        List<Message> newMessages = new ArrayList<>(messages.size());
        newMessages.addAll(messages.subList(0, lastIdx));
        newMessages.add(replaced);
        if (lastIdx + 1 < messages.size()) {
            newMessages.addAll(messages.subList(lastIdx + 1, messages.size()));
        }
        return newMessages;
    }
}
