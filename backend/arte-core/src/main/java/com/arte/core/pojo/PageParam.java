package com.arte.core.pojo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客户端分页条件，可独立引用或通过 {@link BaseParam} 组合使用。
 * <p>
 * 页码从 1 开始；总数、记录、计数开关和数据库排序由服务端管理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
@Getter
@Setter
@ToString
public class PageParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final long MAX_SIZE = 100;

    /**
     * 省略时默认为第一页；显式 null 拒绝。
     */
    @NotNull
    @Min(1)
    private Long current = 1L;

    /**
     * 省略时默认每页 20 条，单页最多 100 条。
     */
    @NotNull
    @Min(1)
    @Max(MAX_SIZE)
    private Long size = 20L;
}
