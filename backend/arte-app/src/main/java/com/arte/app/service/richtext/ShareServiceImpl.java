package com.arte.app.service.richtext;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.richtext.ShareService;
import com.arte.app.common.enums.ResourceTypeEnum;
import com.arte.app.mapper.richtext.ArticleMapper;
import com.arte.app.mapper.richtext.CatalogMapper;
import com.arte.app.mapper.richtext.ShareMapper;
import com.arte.app.pojo.richtext.ArticleDto;
import com.arte.app.pojo.richtext.CatalogDto;
import com.arte.app.pojo.richtext.ShareDto;
import com.arte.core.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分享服务。
 */
@Service
public class ShareServiceImpl extends ServiceImpl<ShareMapper, ShareDto> implements ShareService {

    private static final String TARGET_USER = "USER";
    private static final String TARGET_ROLE = "ROLE";

    @Resource
    private CatalogMapper catalogMapper;

    @Resource
    private ArticleMapper articleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void share(String resourceType, Integer resourceId, String targetType, List<String> targets,
                      String permission, String articlePermission) {
        if (CollUtil.isEmpty(targets)) {
            return;
        }
        for (String target : targets) {
            getBaseMapper().deleteShare(resourceType, resourceId, targetType,
                    TARGET_USER.equals(targetType) ? target : null,
                    TARGET_ROLE.equals(targetType) ? target : null);

            ShareDto share = new ShareDto();
            share.setResourceType(resourceType);
            share.setResourceId(resourceId);
            share.setTargetType(targetType);
            if (TARGET_USER.equals(targetType)) {
                share.setTargetUser(target);
            } else {
                share.setTargetRole(target);
            }
            share.setPermission(permission);
            share.setArticlePermission(articlePermission);
            save(share);
        }
    }

    @Override
    public void unshare(String resourceType, Integer resourceId, String targetType, String target) {
        getBaseMapper().deleteShare(resourceType, resourceId, targetType,
                TARGET_USER.equals(targetType) ? target : null,
                TARGET_ROLE.equals(targetType) ? target : null);
    }

    @Override
    public List<ShareDto> listShares(String resourceType, Integer resourceId) {
        return getBaseMapper().listByResource(resourceType, resourceId);
    }

    @Override
    public void leaveShare(String resourceType, Integer resourceId, String targetUser) {
        ShareDto direct = getBaseMapper().findByResourceAndUser(resourceType, resourceId, targetUser);
        if (direct != null) {
            getBaseMapper().deleteShare(resourceType, resourceId, TARGET_USER, targetUser, null);
            return;
        }

        ShareDto inherited = findInheritedCatalogShare(resourceType, resourceId, targetUser);
        if (inherited == null) {
            throw new BusinessException("分享记录不存在");
        }
        getBaseMapper().deleteShare(ResourceTypeEnum.CATALOG.getValue(), inherited.getResourceId(), TARGET_USER, targetUser, null);
    }

    @Override
    public List<Integer> listSharedCatalogIds(String targetUser, List<String> targetRoles) {
        return getBaseMapper().listSharedCatalogIds(targetUser, targetRoles);
    }

    @Override
    public List<Integer> listSharedArticleIds(String targetUser, List<String> targetRoles) {
        return getBaseMapper().listSharedArticleIds(targetUser, targetRoles);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inheritFromParent(String parentResourceType, Integer parentResourceId,
                                  String childResourceType, Integer childResourceId) {
        // 继承权限在读取时动态计算。
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mergeFromParent(String resourceType, Integer resourceId,
                                String parentResourceType, Integer parentResourceId) {
        // 移动资源后也在读取时动态计算继承权限。
    }

    private ShareDto findInheritedCatalogShare(String resourceType, Integer resourceId, String targetUser) {
        Integer catalogId;
        if (ResourceTypeEnum.ARTICLE == ResourceTypeEnum.of(resourceType)) {
            ArticleDto article = articleMapper.getByIdUnfiltered(resourceId);
            if (article == null) {
                return null;
            }
            catalogId = article.getCatalogId();
        } else {
            CatalogDto catalog = catalogMapper.getByIdUnfiltered(resourceId);
            if (catalog == null) {
                return null;
            }
            catalogId = catalog.getId();
        }
        if (catalogId == null) {
            return null;
        }

        List<Integer> ancestorIds = catalogMapper.listAncestorIds(catalogId);
        if (CollUtil.isEmpty(ancestorIds)) {
            return null;
        }
        for (Integer ancestorId : ancestorIds) {
            ShareDto share = getBaseMapper().findByResourceAndUser(ResourceTypeEnum.CATALOG.getValue(), ancestorId, targetUser);
            if (share != null) {
                return share;
            }
        }
        return null;
    }
}
