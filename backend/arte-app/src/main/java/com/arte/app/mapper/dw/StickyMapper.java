package com.arte.app.mapper.dw;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.dw.StickyDto;
import com.arte.core.annotations.MybatisParams;


/**
 * <p>
 * 便笺表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
@MybatisParams("arte_dw_sticky")
public interface StickyMapper extends BaseMapper<StickyDto> {

}

