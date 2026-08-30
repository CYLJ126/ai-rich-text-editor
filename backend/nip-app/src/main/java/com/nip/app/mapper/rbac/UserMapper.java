package com.nip.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.rbac.UserDto;
import com.nip.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author zhangsc
 * @since 2025/1/15 13:34
 */
@Mapper
@MybatisParams(value = "nip_rbac_user", queryFields = {})
public interface UserMapper extends BaseMapper<UserDto> {

}
