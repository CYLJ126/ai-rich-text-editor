package com.arte.app.service.richtext;

import com.arte.app.common.enums.ResourceTypeEnum;
import com.arte.app.common.enums.richtext.ArticlePermissionEnum;
import com.arte.app.common.enums.richtext.CatalogPermissionEnum;
import com.arte.app.mapper.richtext.ArticleMapper;
import com.arte.app.mapper.richtext.CatalogMapper;
import com.arte.app.mapper.richtext.ShareMapper;
import com.arte.app.pojo.richtext.ArticleDto;
import com.arte.app.pojo.richtext.CatalogDto;
import com.arte.app.pojo.richtext.ShareDto;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PermissionValidator {

    @Resource
    private ShareMapper shareMapper;

    @Resource
    private CatalogMapper catalogMapper;

    @Resource
    private ArticleMapper articleMapper;

    public String getEffectivePermission(String resourceType, Integer resourceId) {
        String currentUser = UserContext.getUserName();
        if (isCatalog(resourceType)) {
            CatalogDto catalog = catalogMapper.getByIdUnfiltered(resourceId);
            if (catalog != null && currentUser.equals(catalog.getCreateBy())) {
                return CatalogPermissionEnum.FULL_CONTROL.getValue();
            }
            ShareDto share = findEffectiveShare(resourceType, resourceId, currentUser);
            return catalogPermission(share);
        }

        ArticleDto article = articleMapper.getByIdUnfiltered(resourceId);
        return article == null
                ? ArticlePermissionEnum.READ.getValue()
                : getEffectiveArticlePermission(article, currentUser);
    }

    public String getEffectivePermission(ArticleDto article) {
        return getEffectiveArticlePermission(article, UserContext.getUserName());
    }

    public boolean canRead(String resourceType, Integer resourceId) {
        String currentUser = UserContext.getUserName();
        if (isCatalog(resourceType)) {
            CatalogDto catalog = catalogMapper.getByIdUnfiltered(resourceId);
            if (catalog == null) return false;
            if (currentUser.equals(catalog.getCreateBy())) return true;
            if (Boolean.TRUE.equals(catalog.getIsPublic())) return true;
        } else {
            ArticleDto article = articleMapper.getByIdUnfiltered(resourceId);
            return canRead(article, currentUser);
        }
        return findEffectiveShare(resourceType, resourceId, currentUser) != null;
    }

    public boolean canRead(ArticleDto article) {
        return canRead(article, UserContext.getUserName());
    }

    public boolean canWrite(String resourceType, Integer resourceId) {
        String permission = getEffectivePermission(resourceType, resourceId);
        if (isCatalog(resourceType)) {
            return CatalogPermissionEnum.of(permission).canDeleteOrGrant();
        }
        return ArticlePermissionEnum.of(permission).canWrite();
    }

    public boolean canComment(String resourceType, Integer resourceId) {
        if (isCatalog(resourceType)) {
            return canWrite(resourceType, resourceId);
        }
        return ArticlePermissionEnum.of(getEffectivePermission(resourceType, resourceId)).canComment();
    }

    public boolean canCreateChild(String resourceType, Integer resourceId) {
        if (!isCatalog(resourceType)) {
            return canWrite(resourceType, resourceId);
        }
        return CatalogPermissionEnum.of(getEffectivePermission(resourceType, resourceId)).canCreateChild();
    }

    public boolean canDelete(String resourceType, Integer resourceId) {
        String permission = getEffectivePermission(resourceType, resourceId);
        if (isCatalog(resourceType)) {
            return CatalogPermissionEnum.of(permission).canDeleteOrGrant();
        }
        return ArticlePermissionEnum.of(permission).canDeleteOrGrant();
    }

    public void assertCanRead(String resourceType, Integer resourceId) {
        if (!canRead(resourceType, resourceId)) {
            throw new BusinessException("无读取权限");
        }
    }

    public String assertCanRead(ArticleDto article) {
        String currentUser = UserContext.getUserName();
        if (currentUser.equals(article.getCreateBy())) {
            return ArticlePermissionEnum.FULL_CONTROL.getValue();
        }
        ShareDto share = findEffectiveShare(article, currentUser);
        if (!Boolean.TRUE.equals(article.getIsPublic()) && share == null) {
            throw new BusinessException("无读取权限");
        }
        return articlePermission(share);
    }

    public void assertCanWrite(String resourceType, Integer resourceId) {
        if (!canWrite(resourceType, resourceId)) {
            throw new BusinessException("无写权限");
        }
    }

    public void assertCanComment(String resourceType, Integer resourceId) {
        if (!canComment(resourceType, resourceId)) {
            throw new BusinessException("无批注权限");
        }
    }

    public void assertCanComment(Integer articleId) {
        if (!Boolean.TRUE.equals(shareMapper.canCommentArticle(articleId, UserContext.getUserName()))) {
            throw new BusinessException("无批注权限");
        }
    }

    public void assertCanCreateChild(String resourceType, Integer resourceId) {
        if (!canCreateChild(resourceType, resourceId)) {
            throw new BusinessException("无新建子内容权限");
        }
    }

    public void assertCanCreateChild(CatalogDto catalog) {
        String currentUser = UserContext.getUserName();
        if (currentUser.equals(catalog.getCreateBy())) {
            return;
        }
        ShareDto share = highestShare(ResourceTypeEnum.CATALOG.getValue(),
                shareMapper.listEffectiveCatalogShares(catalog.getId(), currentUser));
        if (!CatalogPermissionEnum.of(catalogPermission(share)).canCreateChild()) {
            throw new BusinessException("无新建子内容权限");
        }
    }

    public void assertCanDelete(String resourceType, Integer resourceId) {
        if (!canDelete(resourceType, resourceId)) {
            throw new BusinessException("无删除权限");
        }
    }

    private ShareDto findEffectiveShare(String resourceType, Integer resourceId, String user) {
        return highestShare(resourceType, shareMapper.listEffectiveCatalogShares(resourceId, user));
    }

    private ShareDto findEffectiveShare(ArticleDto article, String user) {
        return highestShare(ResourceTypeEnum.ARTICLE.getValue(),
                shareMapper.listEffectiveArticleShares(article.getId(), article.getCatalogId(), user));
    }

    private String getEffectiveArticlePermission(ArticleDto article, String user) {
        if (user.equals(article.getCreateBy())) {
            return ArticlePermissionEnum.FULL_CONTROL.getValue();
        }
        return articlePermission(findEffectiveShare(article, user));
    }

    private boolean canRead(ArticleDto article, String user) {
        if (article == null) return false;
        if (user.equals(article.getCreateBy())) return true;
        if (Boolean.TRUE.equals(article.getIsPublic())) return true;
        return findEffectiveShare(article, user) != null;
    }

    private ShareDto highestShare(String resourceType, List<ShareDto> shares) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        ShareDto best = null;
        for (ShareDto share : shares) {
            best = higherPermission(resourceType, best, share);
        }
        return best;
    }

    private ShareDto higherPermission(String resourceType, ShareDto current, ShareDto candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;

        String currentPermission = isArticle(resourceType)
                ? articlePermission(current)
                : catalogPermission(current);
        String candidatePermission = isArticle(resourceType)
                ? articlePermission(candidate)
                : catalogPermission(candidate);
        String higher = isArticle(resourceType)
                ? ArticlePermissionEnum.higher(currentPermission, candidatePermission)
                : CatalogPermissionEnum.higher(currentPermission, candidatePermission);
        return higher.equals(candidatePermission) ? candidate : current;
    }

    private String catalogPermission(ShareDto share) {
        return share == null
                ? CatalogPermissionEnum.ACCESS.getValue()
                : CatalogPermissionEnum.of(share.getPermission()).getValue();
    }

    private String articlePermission(ShareDto share) {
        if (share == null) {
            return ArticlePermissionEnum.READ.getValue();
        }
        if (isCatalog(share.getResourceType()) && share.getArticlePermission() != null) {
            return ArticlePermissionEnum.of(share.getArticlePermission()).getValue();
        }
        if (isCatalog(share.getResourceType())) {
            return ArticlePermissionEnum.READ.getValue();
        }
        return ArticlePermissionEnum.of(share.getPermission()).getValue();
    }

    private boolean isCatalog(String resourceType) {
        return ResourceTypeEnum.CATALOG == ResourceTypeEnum.of(resourceType);
    }

    private boolean isArticle(String resourceType) {
        return ResourceTypeEnum.ARTICLE == ResourceTypeEnum.of(resourceType);
    }
}
