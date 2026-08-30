package com.arte.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.richtext.ShareDto;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分享关系 Mapper
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/24
 */
@MybatisParams(value = "arte_rt_share", queryFields = {}, insertFields = {MybatisParams.CREATE_BY, MybatisParams.CREATE_TIME})
public interface ShareMapper extends BaseMapper<ShareDto> {

    /** 查询分享给指定用户的目录ID列表 */
    List<Integer> listSharedCatalogIds(@Param("targetUser") String targetUser, @Param("targetRoles") List<String> targetRoles);

    /** 查询分享给指定用户的文章ID列表 */
    List<Integer> listSharedArticleIds(@Param("targetUser") String targetUser, @Param("targetRoles") List<String> targetRoles);

    /** 查询某个资源的所有分享记录 */
    List<ShareDto> listByResource(@Param("resourceType") String resourceType, @Param("resourceId") Integer resourceId);

    /** 删除某个资源的所有分享记录 */
    int deleteByResource(@Param("resourceType") String resourceType, @Param("resourceId") Integer resourceId);

    /** 删除特定分享关系 */
    int deleteShare(@Param("resourceType") String resourceType, @Param("resourceId") Integer resourceId,
                    @Param("targetType") String targetType, @Param("targetUser") String targetUser,
                    @Param("targetRole") String targetRole);

    /** 查询某个资源对特定用户的分享记录 */
    ShareDto findByResourceAndUser(@Param("resourceType") String resourceType, @Param("resourceId") Integer resourceId, @Param("targetUser") String targetUser);

    /** 一次查询文章自身及所属目录祖先链上对指定用户生效的分享记录 */
    List<ShareDto> listEffectiveArticleShares(@Param("articleId") Integer articleId,
                                              @Param("catalogId") Integer catalogId,
                                              @Param("targetUser") String targetUser);

    List<ShareDto> listEffectiveCatalogShares(@Param("catalogId") Integer catalogId,
                                              @Param("targetUser") String targetUser);

    /** 一次查询判断指定用户是否拥有文章批注权限 */
    Boolean canCommentArticle(@Param("articleId") Integer articleId,
                              @Param("targetUser") String targetUser);

    /** 查询分享给指定用户的所有分享记录（用于批量权限判定） */
    @MybatisParams(value = "arte_rt_share", ignore = true)
    List<ShareDto> listByTargetUser(@Param("targetUser") String targetUser, @Param("targetRoles") List<String> targetRoles);
}
