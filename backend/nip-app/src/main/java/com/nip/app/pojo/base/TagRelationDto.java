package com.nip.app.pojo.base;

import com.baomidou.mybatisplus.annotation.TableName;
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
public class TagRelationDto extends TagRelationPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 4118020934168908979L;
}
