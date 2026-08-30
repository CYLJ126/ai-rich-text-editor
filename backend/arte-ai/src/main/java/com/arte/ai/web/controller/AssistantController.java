package com.arte.ai.web.controller;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.arte.ai.api.AssistantService;
import com.arte.ai.pojo.assistant.AssistantDto;
import com.arte.ai.pojo.assistant.AssistantParam;
import com.arte.ai.pojo.assistant.AssistantPo;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/13 20:27 ✾
 **/
@RestController
@RequestMapping("/ai/assistant")
public class AssistantController {

    @Resource
    private AssistantService assistantService;

    /**
     * 新增助手
     */
    @PostMapping("/addAssistant")
    @AnonymousAccess
    public ResultContext<Boolean> addAssistant(@RequestBody AssistantDto dto) {
        dto.setId(null);
        boolean save = assistantService.save(dto);
        if (!save) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 更新助手
     */
    @PostMapping("/updateAssistant")
    @AnonymousAccess
    public ResultContext<Boolean> updateAssistant(@RequestBody AssistantDto dto) {
        boolean update = assistantService.updateById(dto);
        if (!update) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 删除助手
     */
    @PostMapping("/deleteAssistant")
    @AnonymousAccess
    public ResultContext<Void> deleteAssistant(@RequestBody AssistantParam param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.deleteAssistantIdRequired"));
        assistantService.removeById(param.getId());
        return ResultContext.success();
    }

    /**
     * 根据 ID 获取助手
     */
    @PostMapping("/getAssistant")
    @AnonymousAccess
    public ResultContext<AssistantDto> getAssistant(@RequestBody AssistantParam param) {
        if (Boolean.TRUE.equals(param.getDefaultFlag())) {
            QueryWrapper<AssistantDto> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq(AssistantPo.COL_DEFAULT_FLAG, true);
            return ResultContext.success(assistantService.getOne(queryWrapper));
        }
        Assert.notNull(param.getId(), MessageUtils.get("error.field.queryAssistantIdRequired"));
        return ResultContext.success(assistantService.getById(param.getId()));
    }

    /**
     * 查询助手列表
     */
    @PostMapping("/listAssistants")
    @AnonymousAccess
    public PageView<AssistantDto> listAssistants(@RequestBody AssistantParam query) {
        return assistantService.listAssistants(query);
    }

    @AnonymousAccess
    @PostMapping("/toggleAssistantPin")
    public ResultContext<Boolean> toggleAssistantPin(@RequestBody AssistantParam dto) {
        Assert.notNull(dto.getId(), MessageUtils.get("error.field.assistantIdRequired"));
        Assert.notNull(dto.getPinFlag(), MessageUtils.get("error.field.assistantPinRequired"));
        boolean result = assistantService.lambdaUpdate()
                .eq(AssistantDto::getId, dto.getId())
                .set(AssistantDto::getPinFlag, dto.getPinFlag())
                .update();
        return ResultContext.success(result);
    }

    @AnonymousAccess
    @PostMapping("/toggleAssistantStatus")
    public ResultContext<Boolean> toggleAssistantStatus(@RequestBody AssistantParam dto) {
        Assert.notNull(dto.getId(), MessageUtils.get("error.field.assistantIdRequired"));
        Assert.notNull(dto.getStatus(), MessageUtils.get("error.field.assistantStatusRequired"));
        boolean result = assistantService.lambdaUpdate()
                .eq(AssistantDto::getId, dto.getId())
                .set(AssistantDto::getStatus, dto.getStatus())
                .update();
        return ResultContext.success(result);
    }

    /**
     * 设置默认助手
     */
    @Transactional(rollbackFor = Throwable.class)
    @PostMapping("/setAsDefaultAssistant")
    @AnonymousAccess
    public ResultContext<Boolean> setAsDefaultAssistant(@RequestBody AssistantParam param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.defaultAssistantIdRequired"));
        // 将指定助手设为默认
        UpdateWrapper<AssistantDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", param.getId()).set("default_flag", true);
        boolean update = assistantService.update(updateWrapper);
        if (!update) {
            throw new BusinessException(MessageUtils.get("error.ai.assistantSetDefaultFailed", param.getId()));
        }
        // 将其他助手取消默认
        UpdateWrapper<AssistantDto> clearWrapper = new UpdateWrapper<>();
        clearWrapper.ne("id", param.getId()).set("default_flag", false);
        boolean clear = assistantService.update(clearWrapper);
        if (!clear) {
            throw new BusinessException(MessageUtils.get("error.ai.assistantClearDefaultFailed", param.getId()));
        }
        return ResultContext.success();
    }

}
