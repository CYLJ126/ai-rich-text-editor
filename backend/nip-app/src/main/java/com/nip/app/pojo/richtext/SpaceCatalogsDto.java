package com.nip.app.pojo.richtext;

import lombok.Data;

import java.util.List;

/**
 * 三大空间目录树
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/24
 */
@Data
public class SpaceCatalogsDto {

    /** 我的空间（私有） */
    private List<CatalogDto> mySpace;

    /** 与我分享 */
    private List<CatalogDto> sharedWithMe;

    /** 公共空间 */
    private List<CatalogDto> publicSpace;
}
