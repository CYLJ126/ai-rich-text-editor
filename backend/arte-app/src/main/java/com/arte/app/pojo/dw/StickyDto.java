package com.arte.app.pojo.dw;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * @author CYLJ126
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class StickyDto extends StickyPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 37035737851784152L;

    @TableField(exist = false)
    private List<Integer> tags;
}
