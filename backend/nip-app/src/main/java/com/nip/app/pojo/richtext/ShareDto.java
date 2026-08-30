package com.nip.app.pojo.richtext;

import com.nip.core.annotations.MybatisParams;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分享关系传输对象
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/24
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@MybatisParams(value = "nip_rt_share", queryFields = {}, insertFields = {MybatisParams.CREATE_BY, MybatisParams.CREATE_TIME})
public class ShareDto extends SharePo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
