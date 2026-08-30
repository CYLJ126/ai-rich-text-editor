package com.nip.ai.web.controller;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nip.ai.api.AssistantService;
import com.nip.ai.pojo.assistant.AssistantDto;
import com.nip.ai.pojo.assistant.AssistantParam;
import com.nip.ai.pojo.assistant.AssistantPo;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.exception.BusinessException;
import com.nip.core.pojo.PageView;
import com.nip.core.pojo.ResultContext;
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
        Assert.notNull(param.getId(), "待删除助手 ID 不能为空");
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
        Assert.notNull(param.getId(), "待查询助手 ID 不能为空");
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
        Assert.notNull(dto.getId(), "助手 ID 不能为空");
        Assert.notNull(dto.getPinFlag(), "助手是否置顶 不能为空");
        boolean result = assistantService.lambdaUpdate()
                .eq(AssistantDto::getId, dto.getId())
                .set(AssistantDto::getPinFlag, dto.getPinFlag())
                .update();
        return ResultContext.success(result);
    }

    @AnonymousAccess
    @PostMapping("/toggleAssistantStatus")
    public ResultContext<Boolean> toggleAssistantStatus(@RequestBody AssistantParam dto) {
        Assert.notNull(dto.getId(), "助手 ID 不能为空");
        Assert.notNull(dto.getStatus(), "助手状态不能为空");
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
        Assert.notNull(param.getId(), "待设置默认助手 ID 不能为空");
        // 将指定助手设为默认
        UpdateWrapper<AssistantDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", param.getId()).set("default_flag", true);
        boolean update = assistantService.update(updateWrapper);
        if (!update) {
            throw new BusinessException(String.format("助手【%s】设置默认失败", param.getId()));
        }
        // 将其他助手取消默认
        UpdateWrapper<AssistantDto> clearWrapper = new UpdateWrapper<>();
        clearWrapper.ne("id", param.getId()).set("default_flag", false);
        boolean clear = assistantService.update(clearWrapper);
        if (!clear) {
            throw new BusinessException(String.format("助手【%s】设为默认时，取消其他默认助手失败", param.getId()));
        }
        return ResultContext.success();
    }

}
