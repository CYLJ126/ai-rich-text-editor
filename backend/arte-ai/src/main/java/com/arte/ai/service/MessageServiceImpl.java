package com.arte.ai.service;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.MessageService;
import com.arte.ai.common.enums.MessageRoleEnum;
import com.arte.ai.mapper.MessageMapper;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.message.MessageParam;
import com.arte.ai.pojo.message.MessagePo;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 消息服务实现类
 * 不支持 Reactor 响应式自动添加用户条件（创建人、更新人），需手动添加
 * 消息条件按会话 ID 查询和更新，不通过创建人条件判别
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:18 ✾
 **/
@Slf4j
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, MessageDto> implements MessageService {

    @Override
    public PageView<MessageDto> listMessages(MessageParam param) {
        QueryWrapper<MessageDto> queryWrapper = buildQueryWrapper(param);
        // 前端会话列表只展示用户、系统及没有工具调用信息的最终助手消息，排除工具消息和带工具调用信息的中间助手消息；工具协议消息仍保留在数据库中。
        queryWrapper.and(role -> role
                .in(MessagePo.COL_ROLE, MessageRoleEnum.USER, MessageRoleEnum.SYSTEM)
                .or(assistant -> assistant
                        .eq(MessagePo.COL_ROLE, MessageRoleEnum.ASSISTANT)
                        .apply("COALESCE(JSON_LENGTH(" + MessagePo.COL_TOOL_CALLS + "), 0) = 0")));
        return this.page(param, queryWrapper);
    }

    @Override
    public List<MessageDto> selectMainBranchMessages(String convId, Integer limit) {
        if (StrUtil.isBlank(convId) || Objects.isNull(limit) || limit <= 0) {
            return List.of();
        }
        return baseMapper.selectMainBranchMessages(convId, limit);
    }

    @Override
    public List<MessageDto> selectMessagesBefore(String convId, Integer beforeId, Integer limit) {
        if (StrUtil.isBlank(convId) || Objects.isNull(beforeId) || Objects.isNull(limit) || limit <= 0) {
            return List.of();
        }
        return baseMapper.selectMessagesBefore(convId, beforeId, limit);
    }

    @Override
    public List<MessageDto> selectConversationContextMessages(String convId, Integer limit) {
        if (StrUtil.isBlank(convId) || Objects.isNull(limit) || limit <= 0) {
            return List.of();
        }
        return baseMapper.selectConversationContextMessages(convId, limit);
    }

    @Override
    public List<MessageDto> selectConversationContextMessagesBefore(
            String convId, Integer beforeId, Integer limit) {
        if (StrUtil.isBlank(convId) || Objects.isNull(beforeId) || Objects.isNull(limit) || limit <= 0) {
            return List.of();
        }
        return baseMapper.selectConversationContextMessagesBefore(convId, beforeId, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchSoftDelete(Collection<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        baseMapper.batchSoftDelete(messageIds, UserContext.getUserName());
    }

    @Override
    public MessageDto getByMessageId(String messageId) {
        return lambdaQuery()
                .eq(MessageDto::getMessageId, messageId)
                .one();
    }

    @Override
    public void updateWhenResponse(String messageId, String thinkContent, String finishReason, String promptToken, String completionToken, String totalToken, String reasoningToken, Integer firstTokenMs, String errorCode, String errorMsg) {

    }

    private static QueryWrapper<MessageDto> buildQueryWrapper(MessageParam param) {
        return new QueryWrapper<MessageDto>()
                .eq(Objects.nonNull(param.getId()), MessagePo.COL_ID, param.getId())
                .eq(Objects.nonNull(param.getMessageId()), MessagePo.COL_MESSAGE_ID, param.getMessageId())
                .eq(Objects.nonNull(param.getConvId()), MessagePo.COL_CONV_ID, param.getConvId())
                .eq(BooleanUtil.isFalse(param.getIncludeDeleted()), MessagePo.COL_DELETE_FLAG, false);
    }
}
