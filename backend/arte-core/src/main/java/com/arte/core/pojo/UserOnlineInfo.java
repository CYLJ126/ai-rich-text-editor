package com.arte.core.pojo;

import com.arte.core.enums.StatusEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
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

    /**
     * 账号主键；JSON 使用字符串以保留完整的 64 位整数精度。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    /**
     * 登录时核验的账户创建时间；用于拒绝主键被复用后的旧会话，不表示资源归属。
     */
    private LocalDateTime accountCreatedAt;
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
