package com.nip.ai.web.controller;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nip.ai.api.ModelAdapter;
import com.nip.ai.api.ModelConfigService;
import com.nip.ai.pojo.model.ModelConfigDto;
import com.nip.ai.pojo.model.ModelConfigParam;
import com.nip.ai.pojo.model.ModelConfigPo;
import com.nip.ai.strategy.model.ModelAdapterFactory;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.exception.BusinessException;
import com.nip.core.pojo.PageView;
import com.nip.core.pojo.ResultContext;
import com.nip.core.pojo.UserContext;
import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模型配置 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:35 ✾
 **/
@RestController
@RequestMapping("/ai/modelConfig")
public class ModelConfigController {

    @Resource
    private ModelConfigService modelConfigService;

    @Resource
    private ModelAdapterFactory modelAdapterFactory;

    /**
     * 新增模型配置
     */
    @PostMapping("/addModelConfig")
    @AnonymousAccess
    public ResultContext<ModelConfigDto> addModelConfig(@RequestBody ModelConfigDto dto) {
        dto.setId(null);
        ModelConfigDto saved = modelConfigService.addModelConfig(dto);
        if (saved == null) {
            return ResultContext.fail();
        }
        return ResultContext.success(saved);
    }

    /**
     * 更新模型配置
     */
    @PostMapping("/updateModelConfig")
    @AnonymousAccess
    public ResultContext<Boolean> updateModelConfig(@RequestBody ModelConfigDto dto) {
        Boolean update = modelConfigService.updateModelConfig(dto);
        if (!Boolean.TRUE.equals(update)) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 删除模型配置
     */
    @PostMapping("/deleteModelConfig")
    @AnonymousAccess
    public ResultContext<Boolean> deleteModelConfig(@RequestBody ModelConfigParam param) {
        Assert.notNull(param.getId(), "待删除模型配置 ID 不能为空");
        ModelConfigDto existing = modelConfigService.getById(param.getId());
        boolean removed = modelConfigService.removeById(param.getId());
        if (removed && existing != null) {
            modelConfigService.evictDefaultModelConfig(existing.getCreateBy());
        }
        return ResultContext.success(removed);
    }

    /**
     * 设置默认模型配置
     */
    @Transactional(rollbackFor = Throwable.class)
    @PostMapping("/setAsDefaultModelConfig")
    @AnonymousAccess
    public ResultContext<Boolean> setAsDefaultModelConfig(@RequestBody ModelConfigParam param) {
        Assert.notNull(param.getId(), "待设置默认模型配置 ID 不能为空");
        ModelConfigDto existing = modelConfigService.getById(param.getId());
        Assert.notNull(existing, "待设置默认模型配置不存在");
        UpdateWrapper<ModelConfigDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", param.getId()).set(ModelConfigPo.COL_DEFAULT_FLAG, true);
        boolean update = modelConfigService.update(updateWrapper);
        if (!update) {
            throw new BusinessException(String.format("模型【%s】设置默认失败", param.getId()));
        }
        UpdateWrapper<ModelConfigDto> clearWrapper = new UpdateWrapper<>();
        clearWrapper.eq(ModelConfigPo.COL_CREATE_BY, existing.getCreateBy())
                .ne("id", param.getId())
                .set(ModelConfigPo.COL_DEFAULT_FLAG, false);
        modelConfigService.update(clearWrapper);
        modelConfigService.evictDefaultModelConfig(existing.getCreateBy());
        return ResultContext.success();
    }

    /**
     * 根据 ID 获取模型配置
     */
    @PostMapping("/getModelConfig")
    @AnonymousAccess
    public ResultContext<ModelConfigDto> getModelConfig(@RequestBody ModelConfigParam param) {
        if (Boolean.TRUE.equals(param.getDefaultFlag())) {
            QueryWrapper<ModelConfigDto> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq(ModelConfigPo.COL_DEFAULT_FLAG, true);
            queryWrapper.eq(ModelConfigPo.COL_CREATE_BY, StrUtil.blankToDefault(param.getCreateBy(), UserContext.getUserName()));
            return ResultContext.success(modelConfigService.getOne(queryWrapper));
        }
        Assert.notNull(param.getId(), "待获取模型配置 ID 不能为空");
        return ResultContext.success(modelConfigService.getById(param.getId()));
    }

    /**
     * 查询模型配置列表
     */
    @PostMapping("/listModelConfigs")
    @AnonymousAccess
    public PageView<ModelConfigDto> listModelConfigs(@RequestBody ModelConfigParam query) {
        if (StrUtil.isBlank(query.getCreateBy())) {
            query.setCreateBy(UserContext.getUserName());
        }
        return modelConfigService.listModelConfigs(query);
    }

    /**
     * 校验 ApiKey 连通性（发起测试请求）
     */
    @PostMapping("/testConnectivity")
    @AnonymousAccess
    public ResultContext<Boolean> testConnectivity(@RequestBody ModelConfigParam query) {
        return ResultContext.success(modelConfigService.testConnectivity(query.getProvider(), query.getModelId()));
    }

    /**
     * 获取默认模型配置
     */
    @GetMapping("/getDefaultModelConfig")
    @AnonymousAccess
    public ResultContext<ModelConfigDto> getDefaultModelConfig(@RequestBody ModelConfigParam query) {
        ModelAdapter adapter = modelAdapterFactory.getAdapter(query.getProvider(), query.getModelId());
        return ResultContext.wrap(adapter::getOfficialModelConfig);
    }

    @AnonymousAccess
    @PostMapping("/toggleModelConfigPin")
    public ResultContext<Boolean> toggleModelConfigPin(@RequestBody ModelConfigParam param) {
        Assert.notNull(param.getId(), "模型配置 ID 不能为空");
        Assert.notNull(param.getPinFlag(), "模型配置是否置顶 不能为空");
        boolean result = modelConfigService.lambdaUpdate()
                .eq(ModelConfigDto::getId, param.getId())
                .set(ModelConfigDto::getPinFlag, param.getPinFlag())
                .update();
        return ResultContext.success(result);
    }

    @AnonymousAccess
    @PostMapping("/toggleModelConfigStatus")
    public ResultContext<Boolean> toggleModelConfigStatus(@RequestBody ModelConfigParam param) {
        Assert.notNull(param.getId(), "模型配置 ID 不能为空");
        Assert.notNull(param.getStatus(), "模型配置状态不能为空");
        ModelConfigDto existing = modelConfigService.getById(param.getId());
        boolean result = modelConfigService.lambdaUpdate()
                .eq(ModelConfigDto::getId, param.getId())
                .set(ModelConfigDto::getStatus, param.getStatus())
                .update();
        if (result && existing != null) {
            modelConfigService.evictDefaultModelConfig(existing.getCreateBy());
        }
        return ResultContext.success(result);
    }
}
