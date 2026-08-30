package com.nip.app.pojo.base;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.common.enums.TagTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 标签关系表
 * </p>
 *
 * @author zhangsc
 * @since 2025-12-20
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_base_tag_relation")
public class TagRelationPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 7970768004740215791L;
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private TagTypeEnum tagType;

    /**
     * 标签 ID
     */
    private Integer tagId;

    /**
     * 源 ID
     */
    private Integer sourceId;

    public static final String COL_ID = "id";

    public static final String COL_TAG_TYPE = "tag_type";

    public static final String COL_TAG_ID = "tag_id";

    public static final String COL_SOURCE_ID = "source_id";
}
