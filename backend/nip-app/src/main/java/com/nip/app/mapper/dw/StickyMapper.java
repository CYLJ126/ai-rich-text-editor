package com.nip.app.mapper.dw;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.dw.StickyDto;
import com.nip.core.annotations.MybatisParams;


/**
 * <p>
 * 便笺表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
@MybatisParams("nip_dw_sticky")
public interface StickyMapper extends BaseMapper<StickyDto> {

}

