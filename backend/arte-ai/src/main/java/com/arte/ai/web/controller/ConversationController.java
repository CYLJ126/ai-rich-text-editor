package com.arte.ai.web.controller;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import com.arte.ai.api.ConversationService;
import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.conversation.ConversationParam;
import com.arte.ai.pojo.conversation.ConversationUpsertDto;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:35 ✾
 **/
@RestController
@RequestMapping("/ai/conversation")
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    @PostMapping("/createConversation")
    @AnonymousAccess
    public ResultContext<ConversationDto> create(@RequestBody ConversationDto dto) {
        return ResultContext.success(conversationService.createConversation(dto));
    }

    @AnonymousAccess
    @PostMapping("/fullUpdateConversation")
    public ResultContext<Void> fullUpdateConversation(@RequestBody ConversationUpsertDto dto) {
        conversationService.fullUpdateConversation(dto);
        return ResultContext.success();
    }

    @AnonymousAccess
    @PostMapping("/listConversations")
    public PageView<ConversationDto> listConversations(@RequestBody ConversationParam param) {
        return conversationService.listConversations(param);
    }

    @AnonymousAccess
    @PostMapping("/getConversationDetail/{convId}")
    public ResultContext<ConversationDto> getConversationDetail(@PathVariable String convId) {
        return ResultContext.success(conversationService.getAndValidate(convId));
    }

    @AnonymousAccess
    @PostMapping("/deleteConversation/{convId}")
    public ResultContext<Void> deleteConversation(@PathVariable String convId) {
        conversationService.softDelete(convId);
        return ResultContext.success();
    }

    @AnonymousAccess
    @PostMapping("/toggleConversationPin")
    public ResultContext<Boolean> toggleConversationPin(@RequestBody ConversationUpsertDto dto) {
        Assert.notNull(dto.getConvId(), MessageUtils.get("error.ai.convIdRequired"));
        Assert.notNull(dto.getPinFlag(), MessageUtils.get("error.field.conversationPinRequired"));
        boolean result = conversationService.lambdaUpdate()
                .eq(ConversationDto::getConvId, dto.getConvId())
                .set(ConversationDto::getPinFlag, dto.getPinFlag())
                .update();
        return ResultContext.success(result);
    }

    @AnonymousAccess
    @PostMapping("/toggleConversationStatus")
    public ResultContext<Boolean> toggleConversationStatus(@RequestBody ConversationUpsertDto dto) {
        Assert.notNull(dto.getConvId(), MessageUtils.get("error.ai.convIdRequired"));
        Assert.notNull(dto.getStatus(), MessageUtils.get("error.field.conversationStatusRequired"));
        boolean result = conversationService.lambdaUpdate()
                .eq(ConversationDto::getConvId, dto.getConvId())
                .set(ConversationDto::getStatus, dto.getStatus())
                .update();
        return ResultContext.success(result);
    }
}