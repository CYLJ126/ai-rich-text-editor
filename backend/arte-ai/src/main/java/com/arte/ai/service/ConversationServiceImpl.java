package com.arte.ai.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.ConversationService;
import com.arte.ai.common.enums.ContextStrategyEnum;
import com.arte.ai.common.enums.ConversationStatusEnum;
import com.arte.ai.common.enums.ReasoningEffortEnum;
import com.arte.ai.mapper.ConversationMapper;
import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.conversation.ConversationParam;
import com.arte.ai.pojo.conversation.ConversationPo;
import com.arte.ai.pojo.conversation.ConversationUpsertDto;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.TextTypeEnum;
import com.arte.core.exception.ChatException;
import com.arte.core.pojo.PageView;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话服务实现类
 * 不支持 Reactor 响应式自动添加用户条件（创建人、更新人），需手动添加
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:17 ✾
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, ConversationDto> implements ConversationService {
    public final static String NEW_CONVERSATION_TITLE = "新对话";

    @Resource
    private ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationDto createConversation(ConversationDto dto) {
        ConversationDto conv = new ConversationDto();
        conv.setConvId(UUID.randomUUID().toString().replace("-", ""));
        conv.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle() : NEW_CONVERSATION_TITLE);
        conv.setStatus(ConversationStatusEnum.ACTIVE);
        conv.setInteractionType(dto.getInteractionType());
        conv.setScene(dto.getScene());
        // 初始化最后消息时间，以让会话能排序在最前面
        conv.setLastMessageAt(LocalDateTime.now());
        // 默认上下文策略为窗口
        conv.setContextStrategy(ContextStrategyEnum.WINDOW);
        // 默认携带 3 次对话
        conv.setContextWindow(6);
        // 默认推理努力程度为高
        conv.setReasoningEffort(ReasoningEffortEnum.HIGH);
        // 默认文本类型为 Markdown
        conv.setTextType(TextTypeEnum.MARKDOWN);
        save(conv);
        log.info("创建会话成功, convId={}", conv.getConvId());
        return conv;
    }

    @Override
    public void fullUpdateConversation(ConversationUpsertDto upsert) {
        String modelParams = Optional.ofNullable(upsert.getExtraParam())
                .map(map -> objectMapper.writeValueAsString(map)).orElse("{}");
        // 全量更新，允许更新为空值
        UpdateWrapper<ConversationDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ConversationPo.COL_CONV_ID, upsert.getConvId())
                .set(StrUtil.isNotBlank(upsert.getTitle()), ConversationPo.COL_TITLE, upsert.getTitle())
                .set(ConversationPo.COL_ASSISTANT_ID, upsert.getAssistantId())
                .set(ConversationPo.COL_MODEL_ID, upsert.getModelId())
                .set(ConversationPo.COL_KNOWLEDGE_BASE_ID, upsert.getKnowledgeBaseId())
                .set(ConversationPo.COL_CONTEXT_STRATEGY, Optional.ofNullable(upsert.getContextStrategy()).orElse(ContextStrategyEnum.WINDOW))
                .set(ConversationPo.COL_CONTEXT_WINDOW, upsert.getContextWindow())
                .set(ConversationPo.COL_SYSTEM_PROMPT, upsert.getSystemPrompt())
                .set(ConversationPo.COL_EXTRA_PARAM, modelParams)
                .set(ConversationPo.COL_REASONING_EFFORT, upsert.getReasoningEffort())
                .set(ConversationPo.COL_TEXT_TYPE, upsert.getTextType())
                .set(ConversationPo.COL_TEMPERATURE, upsert.getTemperature())
                .set(ConversationPo.COL_MAX_TOKENS, upsert.getMaxTokens())
                .set(ConversationPo.COL_TOP_P, upsert.getTopP())
                .set(ConversationPo.COL_TOP_K, upsert.getTopK())
                .set(ConversationPo.COL_PRESENCE_PENALTY, upsert.getPresencePenalty())
                .set(ConversationPo.COL_FREQUENCY_PENALTY, upsert.getFrequencyPenalty())
                .set(ConversationPo.COL_GLOBAL_MEMORY_FLAG, upsert.getGlobalMemoryFlag())
                .set(ConversationPo.COL_QUERY_REWRITE_FLAG, upsert.getQueryRewriteFlag());
        update(updateWrapper);
    }

    @Override
    public void updateWhenResponse(String convId, String lastMessageId, LocalDateTime lastMessageAt, String lastMessageDigest, Integer messageCount, String userName) {
        log.info("会话 ID：{}，最后一条消息 ID：{}，时间：{}，摘要：{}，本次消息条数：{}", convId, lastMessageId, lastMessageAt, lastMessageDigest, messageCount);
        ConversationDto dbConversation = getAndValidate(convId);
        lambdaUpdate().eq(ConversationDto::getConvId, convId)
                .set(ConversationDto::getLastMessageId, lastMessageId)
                .set(ConversationDto::getLastMessageAt, lastMessageAt)
                .set(StrUtil.isNotBlank(lastMessageDigest), ConversationDto::getLastMessageDigest, lastMessageDigest)
                .set(StrUtil.equalsAny(dbConversation.getTitle(), "", NEW_CONVERSATION_TITLE)
                        && StrUtil.isNotBlank(lastMessageDigest), ConversationDto::getTitle, lastMessageDigest)
                .set(StrUtil.isNotBlank(userName), ConversationDto::getUpdateBy, userName)
                .setSql(String.format("%s = %s + %d", ConversationPo.COL_MESSAGE_COUNT, ConversationPo.COL_MESSAGE_COUNT, messageCount))
                .update();
    }

    @Override
    public void incrementMessageCount(String convId, int delta, String userName) {
        if (delta <= 0) {
            return;
        }
        lambdaUpdate().eq(ConversationDto::getConvId, convId)
                .set(StrUtil.isNotBlank(userName), ConversationDto::getUpdateBy, userName)
                .setSql(String.format("%s = %s + %d", ConversationPo.COL_MESSAGE_COUNT,
                        ConversationPo.COL_MESSAGE_COUNT, delta))
                .update();
    }

    @Override
    public PageView<ConversationDto> listConversations(ConversationParam param) {
        QueryWrapper<ConversationDto> queryWrapper = buildMessageQueryWrapper(param);
        queryWrapper.eq(ConversationPo.COL_STATUS, ConversationStatusEnum.ACTIVE);
        queryWrapper.orderByDesc(ConversationPo.COL_LAST_MESSAGE_AT);
        return this.page(param, queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(String convId) {
        lambdaUpdate()
                .eq(ConversationDto::getConvId, convId)
                .set(ConversationDto::getStatus, ConversationStatusEnum.DELETED)
                .set(ConversationDto::getUpdateTime, LocalDateTime.now())
                .update();
        log.info("软删除会话, convId={}", convId);
    }

    @Override
    public ConversationDto getAndValidate(String convId) {
        ConversationDto one = baseMapper.getAndValidate(convId);
        if (Objects.isNull(one)) {
            throw new ChatException(ResultCodeEnum.WITHOUT_CONVERSATION, "会话不存在: " + convId);
        }
        return one;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoGenerateTitle(String convId, String firstUserContent) {
        if (!StringUtils.hasText(firstUserContent)) return;
        String title = firstUserContent.length() > 20
                ? firstUserContent.substring(0, 20) + "..."
                : firstUserContent;
        lambdaUpdate()
                .eq(ConversationDto::getConvId, convId)
                .set(ConversationDto::getTitle, title)
                .update();
    }

    private static QueryWrapper<ConversationDto> buildMessageQueryWrapper(ConversationParam param) {
        QueryWrapper<ConversationDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(param.getId()), ConversationPo.COL_ID, param.getId())
                .eq(StrUtil.isNotBlank(param.getConvId()), ConversationPo.COL_CONV_ID, param.getConvId())
                .eq(Objects.nonNull(param.getStatus()), ConversationPo.COL_STATUS, param.getStatus())
                .eq(Objects.nonNull(param.getScene()), ConversationPo.COL_SCENE, param.getScene());
        if (CollUtil.isNotEmpty(param.orders())) {
            param.orders().forEach(order -> wrapper.orderBy(true, order.isAsc(), order.getColumn()));
        }
        return wrapper;
    }
}
