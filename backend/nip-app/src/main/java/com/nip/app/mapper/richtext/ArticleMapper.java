package com.nip.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.richtext.ArticleDto;
import com.nip.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文章 Mapper
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@MybatisParams(value = "nip_rt_article", ignore = true)
public interface ArticleMapper extends BaseMapper<ArticleDto> {

    /** 按ID列表查询文章 */
    List<ArticleDto> listByIds(@Param("ids") List<Integer> ids);

    /** 按目录ID或文章ID批量查询文章 */
    List<ArticleDto> listByCatalogIdsOrIds(@Param("catalogIds") List<Integer> catalogIds,
                                           @Param("articleIds") List<Integer> articleIds);

    /** 按ID查询文章（不受拦截器影响） */
    ArticleDto getByIdUnfiltered(@Param("id") Integer id);

    /** 按ID查询文章及完整正文（不受拦截器影响） */
    ArticleDto getByIdWithContentUnfiltered(@Param("id") Integer id);

    /** 按ID查询编辑器所需的文章信息及 JSON 正文（不受拦截器影响） */
    ArticleDto getEditorArticleById(@Param("id") Integer id);

    /** Locks the current article row while a history snapshot is being created. */
    ArticleDto getByIdWithContentForUpdate(@Param("id") Integer id);

    /**
     * 查询所有未删除文章，包含正文，且不受数据权限拦截。
     */
    List<ArticleDto> listAllWithContentUnfiltered();

    List<ArticleDto> listRecentAccessible(@Param("currentUser") String currentUser,
                                          @Param("targetRoles") List<String> targetRoles,
                                          @Param("limit") Integer limit);

    /** 批量计算文章的当前用户有效权限 */
    List<ArticleDto> listEffectivePermissions(@Param("articleIds") List<Integer> articleIds,
                                              @Param("currentUser") String currentUser,
                                              @Param("targetRoles") List<String> targetRoles);

    /** 查询目录内最大的文章排序值 */
    Integer findMaxOrder(@Param("catalogId") Integer catalogId);

    /** 按目录ID查询文章列表（不受拦截器影响） */
    List<ArticleDto> listByCatalogIdUnfiltered(@Param("catalogId") Integer catalogId);

    /** 查询当前用户私有文章及全部公共文章 */
    List<ArticleDto> listOwnedOrPublicArticles(@Param("currentUser") String currentUser);
}
