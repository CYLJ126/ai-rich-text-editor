package com.arte.app.api.richtext;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.richtext.*;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.core.es.EsSearchResponse;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文章服务接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
public interface ArticleService extends IService<ArticleDto> {

    ArticleDto getCompleteArticle(Integer articleId);

    ArticleDto getArticleById(Integer articleId);

    ArticleDto getEditorArticleById(Integer articleId);

    ArticleUpdateStatus updateWithHistory(ArticleDto article);

    List<ArticleHistoryPo> listHistory(Integer articleId);

    ArticleHistoryPo getHistoryById(Long historyId);

    List<ArticleDto> listRecentAccessible(@Param("currentUser") String currentUser,
                                          @Param("targetRoles") List<String> targetRoles,
                                          @Param("limit") Integer limit);

    /**
     * 按目录 ID 查询文章列表（不受拦截器影响）
     */
    List<ArticleDto> listByCatalogIdUnfiltered(@Param("catalogId") Integer catalogId);

    /**
     * 保存文章 Doc 和分块 Doc 到 ES
     *
     * @param articleId 文章 ID
     */
    void saveToEs(Integer articleId);

    void asyncSaveToEs(Integer articleId);

    void asyncDeleteFromEs(Integer articleId);

    /**
     * 保存文章 Doc 到 ES
     *
     * @param article 文章
     * @return 文章文档
     */
    ArticleDocument saveArticleDocToEs(ArticleDto article);

    void asyncSaveArticleDocToEs(ArticleDto article);

    /**
     * 为所有未删除文章重新生成向量并写入 Elasticsearch。
     *
     * @return 重建结果统计
     */
    ArticleEsReindexResult rebuildAllEsIndex();

    /**
     * 移动文章到指定目录
     *
     * @param articleId 文章ID
     * @param catalogId 目标目录ID
     * @return 是否成功
     */
    Boolean moveToCatalog(Integer articleId, Integer catalogId);

    /**
     * 批量更新文章排序
     */
    Boolean reorder(List<ArticleDto> list);

    /**
     * 批量移动文章到指定目录
     *
     * @param articleIds 文章ID列表
     * @param catalogId  目标目录ID
     * @return 是否成功
     */
    Boolean batchMoveToCatalog(List<Integer> articleIds, Integer catalogId);

    /**
     * 获取某目录下最大排序值
     */
    Integer findMaxOrder(Integer catalogId);

    /**
     * 切换文章的公共状态
     */
    void togglePublic(Integer articleId, boolean isPublic, Integer targetCatalogId);

    /**
     * 复制文章到我的空间
     */
    ArticleDto copyToMySpace(Integer sourceArticleId, Integer targetCatalogId);

    /**
     * 发布文章到公共空间
     *
     * @param articleId       要发布的文章ID
     * @param targetCatalogId 公共空间中的目标目录ID
     */
    void publishToPublic(Integer articleId, Integer targetCatalogId);

    /**
     * 混合搜索（BM25 + kNN 合并）
     *
     * @param articleParam 请求参数
     * @return 查询到的文章分块
     */
    EsSearchResponse<ChunkDocument> hybridSearch(ArticleParam articleParam);
}
