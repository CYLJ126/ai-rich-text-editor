package com.nip.app.api.richtext;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.richtext.ShareDto;

import java.util.List;

/**
 * 分享服务接口。
 */
public interface ShareService extends IService<ShareDto> {

    /**
     * 分享资源给指定用户或角色。
     *
     * @param resourceType       资源类型 CATALOG / ARTICLE
     * @param resourceId         资源 ID
     * @param targetType         目标类型 USER / ROLE
     * @param targets            目标用户或角色编码
     * @param permission         文章权限或目录权限
     * @param articlePermission  分享目录时，目录下文章使用的权限
     */
    void share(String resourceType, Integer resourceId, String targetType, List<String> targets,
               String permission, String articlePermission);

    /**
     * 取消分享。
     */
    void unshare(String resourceType, Integer resourceId, String targetType, String target);

    /**
     * 获取某个资源的所有分享记录。
     */
    List<ShareDto> listShares(String resourceType, Integer resourceId);

    /**
     * 用户主动退出分享。
     */
    void leaveShare(String resourceType, Integer resourceId, String targetUser);

    /**
     * 查询分享给指定用户或其角色的所有目录 ID。
     */
    List<Integer> listSharedCatalogIds(String targetUser, List<String> targetRoles);

    /**
     * 查询分享给指定用户或其角色的所有文章 ID。
     */
    List<Integer> listSharedArticleIds(String targetUser, List<String> targetRoles);

    void inheritFromParent(String parentResourceType, Integer parentResourceId,
                           String childResourceType, Integer childResourceId);

    void mergeFromParent(String resourceType, Integer resourceId,
                         String parentResourceType, Integer parentResourceId);
}
