package com.arte.ai.web.controller;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.arte.ai.api.ModelAdapter;
import com.arte.ai.api.ModelConfigService;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelConfigParam;
import com.arte.ai.pojo.model.ModelConfigPo;
import com.arte.ai.pojo.model.AvailableModelDto;
import com.arte.ai.pojo.model.AvailableModelQuery;
import com.arte.ai.service.ModelCatalogService;
import com.arte.ai.strategy.model.ModelAdapterFactory;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @Resource
    private ModelCatalogService modelCatalogService;

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
        return ResultContext.success(maskApiKey(saved));
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
        Assert.notNull(param.getId(), MessageUtils.get("error.field.deleteModelConfigIdRequired"));
        ModelConfigDto existing = getOwnedModel(param.getId());
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
        Assert.notNull(param.getId(), MessageUtils.get("error.field.defaultModelConfigIdRequired"));
        ModelConfigDto existing = getOwnedModel(param.getId());
        UpdateWrapper<ModelConfigDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", param.getId()).set(ModelConfigPo.COL_DEFAULT_FLAG, true);
        boolean update = modelConfigService.update(updateWrapper);
        if (!update) {
            throw new BusinessException(MessageUtils.get("error.ai.modelSetDefaultFailed", param.getId()));
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
            queryWrapper.eq(ModelConfigPo.COL_CREATE_BY, UserContext.getUserName());
            return ResultContext.success(maskApiKey(modelConfigService.getOne(queryWrapper)));
        }
        Assert.notNull(param.getId(), MessageUtils.get("error.field.getModelConfigIdRequired"));
        return ResultContext.success(maskApiKey(getOwnedModel(param.getId())));
    }

    /**
     * 查询模型配置列表
     */
    @PostMapping("/listModelConfigs")
    @AnonymousAccess
    public PageView<ModelConfigDto> listModelConfigs(@RequestBody ModelConfigParam query) {
        query.setCreateBy(UserContext.getUserName());
        PageView<ModelConfigDto> result = modelConfigService.listModelConfigs(query);
        if (result.getRecords() != null) {
            result.getRecords().forEach(this::maskApiKey);
        }
        return result;
    }

    /**
     * 从模型提供商获取当前密钥可用的模型列表。
     */
    @PostMapping("/listAvailableModels")
    @AnonymousAccess
    public ResultContext<List<AvailableModelDto>> listAvailableModels(@RequestBody AvailableModelQuery query) {
        return ResultContext.success(modelCatalogService.listAvailableModels(query));
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
        Assert.notNull(param.getId(), MessageUtils.get("error.field.modelConfigIdRequired"));
        Assert.notNull(param.getPinFlag(), MessageUtils.get("error.field.modelConfigPinRequired"));
        getOwnedModel(param.getId());
        boolean result = modelConfigService.lambdaUpdate()
                .eq(ModelConfigDto::getId, param.getId())
                .eq(ModelConfigDto::getCreateBy, UserContext.getUserName())
                .set(ModelConfigDto::getPinFlag, param.getPinFlag())
                .update();
        return ResultContext.success(result);
    }

    @AnonymousAccess
    @PostMapping("/toggleModelConfigStatus")
    public ResultContext<Boolean> toggleModelConfigStatus(@RequestBody ModelConfigParam param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.modelConfigIdRequired"));
        Assert.notNull(param.getStatus(), MessageUtils.get("error.field.modelConfigStatusRequired"));
        ModelConfigDto existing = getOwnedModel(param.getId());
        boolean result = modelConfigService.lambdaUpdate()
                .eq(ModelConfigDto::getId, param.getId())
                .eq(ModelConfigDto::getCreateBy, UserContext.getUserName())
                .set(ModelConfigDto::getStatus, param.getStatus())
                .update();
        if (result && existing != null) {
            modelConfigService.evictDefaultModelConfig(existing.getCreateBy());
        }
        return ResultContext.success(result);
    }

    private ModelConfigDto getOwnedModel(Integer id) {
        ModelConfigDto model = modelConfigService.getOwnedModel(id, UserContext.getUserName());
        if (model == null) {
            throw new BusinessException("error.ai.modelConfigNotAccessible");
        }
        return model;
    }

    private ModelConfigDto maskApiKey(ModelConfigDto model) {
        if (model != null) {
            model.setMaskedApiKey(StrUtil.isBlank(model.getApiKey()) ? null : ModelConfigService.MASKED_API_KEY);
            model.setApiKey(model.getMaskedApiKey());
        }
        return model;
    }
}
