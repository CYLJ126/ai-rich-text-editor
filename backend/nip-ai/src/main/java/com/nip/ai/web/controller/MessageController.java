package com.nip.ai.web.controller;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nip.ai.api.ConversationService;
import com.nip.ai.api.MessageService;
import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.ai.pojo.message.MessageParam;
import com.nip.ai.pojo.message.MessagePo;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.pojo.PageView;
import com.nip.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * AI 消息管理 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:35 ✾
 */
@RestController
@RequestMapping("/ai/message")
@RequiredArgsConstructor
public class MessageController {

    @Resource
    private MessageService messageService;

    @Resource
    private ConversationService conversationService;

    /**
     * 查询会话消息列表
     */
    @PostMapping("/listMessages")
    @AnonymousAccess
    public PageView<MessageDto> listMessages(@RequestBody MessageParam param) {
        return messageService.listMessages(param);
    }

    /**
     * 获取消息详情
     */
    @GetMapping("/{messageId}")
    @AnonymousAccess
    public ResultContext<MessageDto> detail(@PathVariable String messageId) {
        return ResultContext.success(messageService.getByMessageId(messageId));
    }

    /**
     * 批量软删除消息
     */
    @PostMapping("/batchDeleteMessages")
    @AnonymousAccess
    public ResultContext<Void> batchDeleteMessages(@RequestBody MessageParam param) {
        Assert.notNull(param.getMessageIds(), "messageIds 不能为空");
        Assert.notNull(param.getConvId(), "convId 不能为空");
        messageService.batchSoftDelete(param.getMessageIds());
        CompletableFuture.runAsync(() -> {
            // 删除后更新会话 messageCount 字段
            QueryWrapper<MessageDto> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq(MessagePo.COL_CONV_ID, param.getConvId())
                    .eq(MessagePo.COL_DELETE_FLAG, Boolean.FALSE);
            int count = Math.toIntExact(messageService.count(queryWrapper));
            conversationService.lambdaUpdate().eq(ConversationDto::getConvId, param.getConvId())
                    .set(ConversationDto::getMessageCount, count)
                    .update();
        });
        return ResultContext.success();
    }

    /**
     * 点赞/点踩
     */
    @PostMapping("/toggleMessageLike")
    @AnonymousAccess
    public ResultContext<Void> toggleMessageLike(@RequestBody MessageParam param) {
        Assert.notNull(param.getMessageId(), "messageId 不能为空");
        Assert.notNull(param.getLikeStatus(), "likeStatus 不能为空");
        messageService.lambdaUpdate().eq(MessageDto::getMessageId, param.getMessageId())
                .set(MessageDto::getLikeStatus, param.getLikeStatus())
                .update();
        return ResultContext.success();
    }
}