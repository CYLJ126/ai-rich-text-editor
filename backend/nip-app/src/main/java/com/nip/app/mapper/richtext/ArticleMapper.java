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

    /** 查询公共文章列表 */
    List<ArticleDto> listPublicArticles();

    /** 按ID列表查询文章 */
    List<ArticleDto> listByIds(@Param("ids") List<Integer> ids);

    /** 按ID查询文章（不受拦截器影响） */
    ArticleDto getByIdUnfiltered(@Param("id") Integer id);

    /** 按ID查询文章及完整正文（不受拦截器影响） */
    ArticleDto getByIdWithContentUnfiltered(@Param("id") Integer id);

    /** 按ID查询编辑器所需的文章信息及 JSON 正文（不受拦截器影响） */
    ArticleDto getEditorArticleById(@Param("id") Integer id);

    /** 按ID查询文章完整纯文本正文 */
    String getContentTextById(@Param("id") Integer id);

    /** Locks the current article row while a history snapshot is being created. */
    ArticleDto getByIdWithContentForUpdate(@Param("id") Integer id);

    /**
     * 查询所有未删除文章，包含正文，且不受数据权限拦截。
     */
    List<ArticleDto> listAllWithContentUnfiltered();

    List<ArticleDto> listRecentAccessible(@Param("currentUser") String currentUser,
                                          @Param("targetRoles") List<String> targetRoles,
                                          @Param("limit") Integer limit);

    /** 按目录ID查询文章列表（不受拦截器影响） */
    List<ArticleDto> listByCatalogIdUnfiltered(@Param("catalogId") Integer catalogId);

    /** 查询当前用户的私有文章（排除已发布至公共空间的） */
    List<ArticleDto> listMyPrivateArticles(@Param("currentUser") String currentUser);
}
