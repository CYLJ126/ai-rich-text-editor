package com.arte.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author zhangsc
 * @since 2025/1/15 13:34
 */
@Mapper
@MybatisParams(value = "arte_rbac_user", queryFields = {})
public interface UserMapper extends BaseMapper<UserDto> {

}
