package com.arte.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.ai.pojo.model.ModelConfigDto;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 模型配置表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
public interface ModelConfigMapper extends BaseMapper<ModelConfigDto> {
    /**
     * 清除同一 modelType 下所有默认标记
     *
     * @param modelType 模型类型
     */
    void clearDefaultByModelType(@Param("modelType") String modelType);

    /**
     * 批量更新排序
     *
     * @param sortList 排序列表
     */
    void batchUpdateSort(@Param("sortList") List<ModelConfigDto> sortList);

}
