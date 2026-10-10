package com.arte.app.mapper.rbac;

import com.arte.app.pojo.rbac.UserDto;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author zhangsc
 * @since 2025/1/15 13:34
 */
@Mapper
@MybatisParams(value = "arte_rbac_user", queryFields = {})
public interface UserMapper extends BaseMapper<UserDto>, AutoCloseable {

    /**
     * 登录专用查询：一次读取当前账号资料和密码，不使用账号或 MyBatis 查询缓存。
     * 由登录服务暂停外层路由事务后调用，避免沿用其他库的已绑定连接。
     */
    List<UserDto> selectLoginByName(@Param("name") String name);

    /**
     * 身份核验只读取账户主键、名称、状态和创建时间，不加载密码或权限集合。
     * 每次执行 SQL，并清理当前会话查询缓存，避免停用／改名后继续使用旧快照。
     */
    List<UserDto> selectIdentityByName(@Param("name") String name);

    List<UserDto> selectIdentityById(@Param("id") long id);

    /**
     * 当前读必须由调用方提供 app 物理库事务；锁持续到该事务结束。
     */
    List<UserDto> selectCurrentIdentityById(@Param("id") long id);
}
