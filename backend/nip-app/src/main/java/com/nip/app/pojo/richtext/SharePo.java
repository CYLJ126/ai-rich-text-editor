package com.nip.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分享关系实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/24
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_rt_share")
public class SharePo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 资源类型：CATALOG / ARTICLE */
    private String resourceType;

    /**
     * 目录 ID 或文章 ID
     */
    private Integer resourceId;

    /** 被分享目标用户名 */
    private String targetUser;

    /** 分享目标类型：USER / ROLE */
    private String targetType;

    /** 被分享目标角色编码 */
    private String targetRole;

    /** Resource permission. ARTICLE uses article permissions; CATALOG uses catalog permissions. */
    private String permission;

    /** Article permission inherited from a CATALOG share. */
    private String articlePermission;

    /** 分享创建人 */
    private String createBy;

    /** 分享时间 */
    private LocalDateTime createTime;
}
