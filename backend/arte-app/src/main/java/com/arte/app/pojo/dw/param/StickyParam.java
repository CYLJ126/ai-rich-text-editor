package com.arte.app.pojo.dw.param;

import com.arte.app.pojo.BaseParam;
import com.arte.app.pojo.dw.StickyDto;
import com.arte.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 11:15 ✾
 */
@Getter
@Setter
@ToString(callSuper = true)
public class StickyParam extends BaseParam<StickyDto> implements Serializable {
    @Serial
    private static final long serialVersionUID = -6164550557119351105L;
    private String title;
    private StatusEnum status;
    private String content;
}
