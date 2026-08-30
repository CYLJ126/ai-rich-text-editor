package com.arte.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelConfigParam;
import com.arte.core.pojo.PageView;

/**
 * AI 模型配置 Service 接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 16:46 ✾
 **/
public interface ModelConfigService extends IService<ModelConfigDto> {

    /**
     * 新增模型配置
     *
     * @param dto 配置请求DTO
     * @return 新增的配置VO
     */
    ModelConfigDto addModelConfig(ModelConfigDto dto);

    /**
     * 更新模型配置
     *
     * @param dto      更新请求DTO
     */
    Boolean updateModelConfig(ModelConfigDto dto);

    /**
     * 按条件查询模型配置列表
     *
     * @param param 查询条件
     * @return 配置VO列表
     */
    PageView<ModelConfigDto> listModelConfigs(ModelConfigParam param);

    ModelConfigDto getDefaultModelConfig(String userName);

    /**
     * 清除指定用户的默认模型缓存。
     *
     * @param userName 用户名
     */
    void evictDefaultModelConfig(String userName);

    /**
     * 校验 ApiKey 连通性（发起测试请求）
     *
     * @param provider 配置提供者
     * @param modelId  模型ID
     * @return 是否连通
     */
    Boolean testConnectivity(ModelProviderEnum provider, String modelId);

}
