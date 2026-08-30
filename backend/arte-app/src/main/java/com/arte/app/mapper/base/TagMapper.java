package com.arte.app.mapper.base;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.base.TagDto;
import com.arte.app.pojo.base.param.TagParam;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.List;


/**
 * <p>
 * 标签表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@MybatisParams("arte_base_tag")
public interface TagMapper extends BaseMapper<TagDto> {

    /**
     * 根据父标签查找子标签（包括本身）
     *
     * @param param 查询参数
     * @return 标签列表
     */
    @MybatisParams("rec_query")
    List<TagDto> listRecursive(TagParam param);

    /**
     * 根据子标签查找父标签（包括本身）
     *
     * @param param 查询参数
     * @return 标签列表
     */
    @MybatisParams("rec_query")
    List<TagDto> listAntiRecursive(TagParam param);

    Boolean updateOrder(List<TagDto> list);

    Boolean removeRecursive(@Param("id") Integer id);

    List<TagDto> listByParam(TagParam param);
}

