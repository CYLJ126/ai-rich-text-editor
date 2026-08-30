package com.arte.app.api.richtext;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.richtext.ArticleDto;
import com.arte.app.pojo.richtext.CatalogDto;
import com.arte.app.pojo.richtext.SpaceCatalogsDto;

import java.util.List;

/**
 * 目录服务接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
public interface CatalogService extends IService<CatalogDto> {

    /**
     * 查询目录树（递归返回所有子目录）
     */
    List<CatalogDto> listRecursive(CatalogDto param);

    /**
     * 获取某父目录下最大排序值
     */
    Integer findMaxOrder(Integer fatherId);

    /**
     * 批量排序
     */
    Boolean reorder(List<CatalogDto> list, Integer begin, Boolean isAsc);

    /**
     * 递归删除目录及所有子目录
     */
    Boolean removeRecursive(Integer id);

    /**
     * 获取三大空间的目录树（我的空间 / 与我分享 / 公共空间）
     */
    SpaceCatalogsDto listSpaceCatalogs();

    List<ArticleDto> listByIds(List<Integer> ids);

    /**
     * 切换目录的公共状态
     */
    void togglePublic(Integer catalogId, boolean isPublic, Integer targetCatalogId);

    /**
     * 复制目录（含子目录和文章）到我的空间
     */
    CatalogDto copyToMySpace(Integer sourceCatalogId, Integer targetCatalogId);

    /**
     * 发布目录到公共空间
     *
     * @param catalogId       要发布的目录ID
     * @param targetCatalogId 公共空间中的目标父目录ID
     */
    void publishToPublic(Integer catalogId, Integer targetCatalogId);
}
