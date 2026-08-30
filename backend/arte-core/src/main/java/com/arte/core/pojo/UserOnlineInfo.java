package com.arte.core.pojo;

import com.arte.core.enums.StatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 用户在线信息
 *
 * @author zhangsc
 * @since 2025/2/5 14:58
 */
@Data
@Accessors(chain = true)
public class UserOnlineInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 5138452000055087262L;

    private Integer id;
    private String token;
    private String userName;
    private String password;
    private String mobile;
    private String email;
    private String ip;
    private String browser;
    private String address;
    private String avatar;
    private String signature;
    private List<String> tags;
    /**
     * 状态
     */
    private StatusEnum status;

    /**
     * token
     */
    private String key;

    /**
     * 登录时间
     */
    private Date loginTime;

    /**
     * 角色信息
     */
    private Collection<String> roles;

    /**
     * 菜单信息
     */
    private Collection<String> menus;

    /**
     * 操作权限信息
     */
    private Collection<String> menuOperations;
    /**
     * 部门信息
     */
    private Collection<String> departments;
    /**
     * 所属组信息
     */
    private Collection<String> groups;
}
