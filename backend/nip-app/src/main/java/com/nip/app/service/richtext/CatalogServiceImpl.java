package com.nip.app.service.richtext;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.app.api.richtext.ArticleService;
import com.nip.app.api.richtext.CatalogService;
import com.nip.app.api.richtext.ShareService;
import com.nip.app.common.enums.ResourceTypeEnum;
import com.nip.app.common.enums.richtext.ArticlePermissionEnum;
import com.nip.app.common.enums.richtext.CatalogPermissionEnum;
import com.nip.app.mapper.rbac.RbacRelationMapper;
import com.nip.app.mapper.richtext.ArticleMapper;
import com.nip.app.mapper.richtext.CatalogMapper;
import com.nip.app.mapper.richtext.ShareMapper;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.app.pojo.richtext.*;
import com.nip.core.exception.BusinessException;
import com.nip.core.pojo.UserContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 目录服务实现
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Service
public class CatalogServiceImpl extends ServiceImpl<CatalogMapper, CatalogDto> implements CatalogService {

    @Resource
    private CatalogMapper catalogMapper;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private ArticleService articleService;

    @Resource
    private ShareService shareService;

    @Resource
    private ShareMapper shareMapper;

    @Resource
    private RbacRelationMapper rbacRelationMapper;

    @Override
    public List<CatalogDto> listRecursive(CatalogDto param) {
        List<CatalogDto> list = catalogMapper.listAll(param);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        List<CatalogDto> tree = buildTree(list);

        // 查询所有文章，按 catalogId 分组挂到对应目录节点上
        List<ArticleDto> allArticles = articleService.list();
        if (CollUtil.isNotEmpty(allArticles)) {
            Map<Integer, List<ArticleDto>> articleMap = allArticles.stream()
                    .filter(a -> Objects.nonNull(a.getCatalogId()))
                    .collect(Collectors.groupingBy(ArticleDto::getCatalogId));
            attachArticles(tree, articleMap);
        }
        return tree;
    }

    /**
     * 递归将文章列表挂到对应目录节点上
     */
    private void attachArticles(List<CatalogDto> list, Map<Integer, List<ArticleDto>> articleMap) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        for (CatalogDto catalog : list) {
            List<ArticleDto> articles = articleMap.get(catalog.getId());
            catalog.setArticles(Objects.nonNull(articles) ? articles : Collections.emptyList());
            if (CollUtil.isNotEmpty(catalog.getChildren())) {
                attachArticles(catalog.getChildren(), articleMap);
            }
        }
    }

    @Override
    public Integer findMaxOrder(Integer fatherId) {
        QueryWrapper<CatalogDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(Objects.nonNull(fatherId), CatalogPo.COL_FATHER_ID, fatherId);
        queryWrapper.isNull(fatherId == null, CatalogPo.COL_FATHER_ID);
        queryWrapper.orderBy(true, false, CatalogPo.COL_ORDER_ID);
        List<CatalogDto> list = list(queryWrapper);
        return CollUtil.isEmpty(list) ? 0 : list.getFirst().getOrderId();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Boolean reorder(List<CatalogDto> list, Integer begin, Boolean isAsc) {
        if (CollUtil.isEmpty(list)) {
            return true;
        }
        int temp = begin == null ? 1 : begin;
        boolean success = true;
        for (CatalogDto catalog : list) {
            if (catalog.getOrderId() == null) {
                catalog.setOrderId(BooleanUtil.isTrue(isAsc) ? temp++ : temp--);
            }
            catalog.setUpdateBy(UserContext.getUserName());
            catalog.setUpdateTime(LocalDateTime.now());
            success = catalogMapper.updateOrder(catalog) && success;
        }
        return success;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Boolean removeRecursive(Integer id) {
        List<Integer> catalogIds = new ArrayList<>(catalogMapper.listDescendantIds(id));
        catalogIds.add(id);

        List<Integer> articleIds = new ArrayList<>();
        for (Integer catalogId : catalogIds) {
            List<ArticleDto> articles = articleMapper.listByCatalogIdUnfiltered(catalogId);
            if (CollUtil.isNotEmpty(articles)) {
                for (ArticleDto article : articles) {
                    articleIds.add(article.getId());
                    shareMapper.deleteByResource(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
                }
            }
            shareMapper.deleteByResource(ResourceTypeEnum.CATALOG.getValue(), catalogId);
        }

        if (CollUtil.isNotEmpty(articleIds)) {
            articleService.removeByIds(articleIds);
        }
        return catalogMapper.removeRecursive(id);
    }

    /**
     * 将平面列表构建为树形结构
     */
    private List<CatalogDto> buildTree(List<CatalogDto> flatList) {
        List<CatalogDto> rootList = new ArrayList<>();
        Map<Integer, CatalogDto> map = flatList.stream()
                .collect(Collectors.toMap(CatalogPo::getId, c -> c));
        flatList.forEach(item -> {
            if (Objects.isNull(item.getFatherId())) {
                rootList.add(item);
            } else {
                CatalogDto parent = map.get(item.getFatherId());
                if (Objects.nonNull(parent)) {
                    if (Objects.isNull(parent.getChildren())) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(item);
                } else {
                    rootList.add(item);
                }
            }
        });
        // 按 orderId 排序
        sortChildren(rootList);
        return rootList;
    }

    private void sortChildren(List<CatalogDto> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        list.sort(Comparator.comparing(CatalogDto::getOrderId, Comparator.nullsLast(Integer::compareTo)));
        for (CatalogDto item : list) {
            if (CollUtil.isNotEmpty(item.getChildren())) {
                sortChildren(item.getChildren());
            }
        }
    }

    @Override
    public SpaceCatalogsDto listSpaceCatalogs() {
        String currentUser = UserContext.getUserName();
        List<String> currentRoles = listRoleCodes(currentUser);

        // 一次性加载当前用户的所有分享记录，后续在内存中判定权限
        List<ShareDto> allShares = shareMapper.listByTargetUser(currentUser, currentRoles);
        Map<String, ShareDto> shareMap = CollUtil.isEmpty(allShares)
                ? Collections.emptyMap()
                : allShares.stream().collect(Collectors.toMap(
                        s -> s.getResourceType() + ":" + s.getResourceId(),
                        s -> s,
                        this::higherShare));

        // 1. 我的空间
        CatalogDto myParam = new CatalogDto();
        myParam.setCreateBy(currentUser);
        List<CatalogDto> myCatalogs = catalogMapper.listMyPrivateCatalogs(myParam);
        List<CatalogDto> myTree = buildTree(myCatalogs);
        List<ArticleDto> myArticles = articleMapper.listMyPrivateArticles(currentUser);
        attachArticlesByMap(myTree, myArticles);
        setEffectivePermissions(myTree, shareMap, null, null);

        // 2. 与我分享
        List<Integer> sharedCatalogIds = shareService.listSharedCatalogIds(currentUser, currentRoles);
        List<Integer> sharedArticleIds = shareService.listSharedArticleIds(currentUser, currentRoles);
        List<CatalogDto> sharedTree = buildSharedTree(sharedCatalogIds, sharedArticleIds);
        setEffectivePermissions(sharedTree, shareMap, null, null);

        // 3. 公共空间
        List<CatalogDto> publicCatalogs = catalogMapper.listPublicCatalogs();
        List<CatalogDto> publicTree = buildTree(publicCatalogs);
        List<ArticleDto> publicArticles = articleMapper.listPublicArticles();
        attachArticlesByMap(publicTree, publicArticles);
        setEffectivePermissions(publicTree, shareMap, null, null);

        SpaceCatalogsDto result = new SpaceCatalogsDto();
        result.setMySpace(myTree);
        result.setSharedWithMe(sharedTree);
        result.setPublicSpace(publicTree);
        return result;
    }

    /** 递归为树节点及其文章设置当前用户的有效权限（分享权限从预加载的 shareMap 内存判定） */
    private void setEffectivePermissions(List<CatalogDto> tree, Map<String, ShareDto> shareMap,
                                         String inheritedCatalogPermission,
                                         String inheritedArticlePermission) {
        if (CollUtil.isEmpty(tree)) return;
        String currentUser = UserContext.getUserName();
        for (CatalogDto node : tree) {
            if (currentUser.equals(node.getCreateBy())) {
                node.setEffectivePermission(CatalogPermissionEnum.FULL_CONTROL.getValue());
            } else {
                ShareDto directShare = shareMap.get(shareKey(ResourceTypeEnum.CATALOG, node.getId()));
                node.setEffectivePermission(maxCatalogPermission(
                        directShare == null ? null : directShare.getPermission(),
                        inheritedCatalogPermission));
            }

            String childInheritedCatalog = inheritedCatalogPermission;
            String childInheritedArticle = inheritedArticlePermission;
            ShareDto nodeShare = shareMap.get(shareKey(ResourceTypeEnum.CATALOG, node.getId()));
            if (nodeShare != null) {
                childInheritedCatalog = maxCatalogPermission(childInheritedCatalog, nodeShare.getPermission());
                childInheritedArticle = maxArticlePermission(childInheritedArticle, inheritedArticlePermission(nodeShare));
            }

            if (CollUtil.isNotEmpty(node.getArticles())) {
                setArticleEffectivePermissions(node.getArticles(), shareMap, childInheritedArticle);
            }
            if (CollUtil.isNotEmpty(node.getChildren())) {
                setEffectivePermissions(node.getChildren(), shareMap, childInheritedCatalog, childInheritedArticle);
            }
        }
    }

    /** 为文章列表设置当前用户的有效权限（分享权限从预加载的 shareMap 内存判定） */
    private void setArticleEffectivePermissions(List<ArticleDto> articles, Map<String, ShareDto> shareMap, String inheritedSharePermission) {
        if (CollUtil.isEmpty(articles)) return;
        String currentUser = UserContext.getUserName();
        for (ArticleDto a : articles) {
            if (currentUser.equals(a.getCreateBy())) {
                a.setEffectivePermission(ArticlePermissionEnum.FULL_CONTROL.getValue());
            } else {
                ShareDto directShare = shareMap.get(shareKey(ResourceTypeEnum.ARTICLE, a.getId()));
                a.setEffectivePermission(maxArticlePermission(
                        directShare == null ? null : directShare.getPermission(),
                        inheritedSharePermission));
            }
        }
    }

    private ShareDto higherShare(ShareDto first, ShareDto second) {
        if (ResourceTypeEnum.CATALOG == ResourceTypeEnum.of(first.getResourceType())) {
            ShareDto merged = copyShareBase(first);
            merged.setPermission(maxCatalogPermission(first.getPermission(), second.getPermission()));
            merged.setArticlePermission(maxArticlePermission(
                    inheritedArticlePermission(first),
                    inheritedArticlePermission(second)));
            return merged;
        }
        String higher = maxArticlePermission(first.getPermission(), second.getPermission());
        return higher.equals(ArticlePermissionEnum.of(first.getPermission()).getValue()) ? first : second;
    }

    private ShareDto copyShareBase(ShareDto source) {
        ShareDto copy = new ShareDto();
        copy.setId(source.getId());
        copy.setResourceType(source.getResourceType());
        copy.setResourceId(source.getResourceId());
        copy.setTargetType(source.getTargetType());
        copy.setTargetUser(source.getTargetUser());
        copy.setTargetRole(source.getTargetRole());
        copy.setCreateBy(source.getCreateBy());
        copy.setCreateTime(source.getCreateTime());
        return copy;
    }

    private String shareKey(ResourceTypeEnum resourceType, Integer resourceId) {
        return resourceType.getValue() + ":" + resourceId;
    }

    private String maxCatalogPermission(String first, String second) {
        return CatalogPermissionEnum.higher(first, second);
    }

    private String maxArticlePermission(String first, String second) {
        return ArticlePermissionEnum.higher(first, second);
    }

    private String inheritedArticlePermission(ShareDto share) {
        return share.getArticlePermission() == null
                ? ArticlePermissionEnum.READ.getValue()
                : share.getArticlePermission();
    }

    private List<String> listRoleCodes(String user) {
        List<RoleDto> roles = rbacRelationMapper.listRolesByUserName(user);
        if (CollUtil.isEmpty(roles)) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(RoleDto::getRoleCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 构建"与我分享"目录树
     */
    private List<CatalogDto> buildSharedTree(List<Integer> sharedCatalogIds, List<Integer> sharedArticleIds) {
        List<CatalogDto> result = new ArrayList<>();
        Set<Integer> allCatalogIds = new LinkedHashSet<>();

        // 收集被分享目录及其所有子孙目录
        if (CollUtil.isNotEmpty(sharedCatalogIds)) {
            for (Integer cid : sharedCatalogIds) {
                allCatalogIds.add(cid);
                allCatalogIds.addAll(catalogMapper.listDescendantIds(cid));
            }
        }


        // 一次性加载所有相关目录
        if (CollUtil.isNotEmpty(allCatalogIds)) {
            List<CatalogDto> allCatalogs = catalogMapper.listByIds(new ArrayList<>(allCatalogIds));
            List<CatalogDto> tree = buildTree(allCatalogs);
            // 只保留被分享的根节点
            Set<Integer> rootIds = new HashSet<>(sharedCatalogIds);
            for (CatalogDto node : tree) {
                if (rootIds.contains(node.getId())) {
                    result.add(node);
                }
            }
            List<ArticleDto> inheritedArticles = new ArrayList<>();
            for (Integer catalogId : allCatalogIds) {
                inheritedArticles.addAll(articleMapper.listByCatalogIdUnfiltered(catalogId));
            }
            attachArticlesByMap(result, inheritedArticles);
        }

        // 挂接被分享的文章
        if (CollUtil.isNotEmpty(sharedArticleIds)) {
            List<ArticleDto> sharedArticles = articleMapper.listByIds(sharedArticleIds);

            // 文章能挂到树中目录的就挂上去，挂不上去的（目录不在树中或没有目录）创建虚拟节点
            // 目录不在树中（含没有目录）的文章，创建虚拟目录节点
            List<ArticleDto> unplaced = sharedArticles.stream()
                    .filter(a -> Objects.isNull(a.getCatalogId()) || !containsCatalog(result, a.getCatalogId()))
                    .toList();

            if (CollUtil.isNotEmpty(unplaced)) {
                // 目录不在树中的文章归到一个虚拟节点下
                CatalogDto wrapper = new CatalogDto();
                wrapper.setId(-1);
                wrapper.setName("分享的文章");
                wrapper.setOrderId(0);
                wrapper.setArticles(unplaced);
                // 虚拟节点的权限取所有文章中最高权限
                wrapper.setEffectivePermission(CatalogPermissionEnum.ACCESS.getValue());
                result.add(wrapper);
            }
        }

        sortChildren(result);
        return result;
    }

    @Override
    public List<ArticleDto> listByIds(List<Integer> ids) {
        return articleMapper.listByIds(ids);
    }

    /** 递归检查树中是否包含指定目录ID */
    private boolean containsCatalog(List<CatalogDto> tree, Integer catalogId) {
        if (CollUtil.isEmpty(tree) || catalogId == null) return false;
        for (CatalogDto node : tree) {
            if (catalogId.equals(node.getId())) return true;
            if (containsCatalog(node.getChildren(), catalogId)) return true;
        }
        return false;
    }

    private void attachArticlesByMap(List<CatalogDto> tree, List<ArticleDto> articles) {
        if (CollUtil.isEmpty(articles)) return;
        Map<Integer, List<ArticleDto>> articleMap = articles.stream()
                .filter(a -> Objects.nonNull(a.getCatalogId()))
                .collect(Collectors.groupingBy(ArticleDto::getCatalogId));
        attachArticles(tree, articleMap);
    }

    @Override
    public void togglePublic(Integer catalogId, boolean isPublic, Integer targetCatalogId) {
        if (!isPublic) {
            // 撤回公共状态：递归处理子孙目录和文章
            List<Integer> allIds = catalogMapper.listDescendantIds(catalogId);
            allIds.add(catalogId);
            if (targetCatalogId != null) {
                if (allIds.contains(targetCatalogId)) {
                    throw new BusinessException("目录不能移动到自己的子目录中");
                }
                CatalogDto targetCatalog = catalogMapper.getByIdUnfiltered(targetCatalogId);
                if (targetCatalog == null) {
                    throw new BusinessException("目标目录不存在");
                }
                if (Boolean.TRUE.equals(targetCatalog.getIsPublic())) {
                    throw new BusinessException("目录撤回后不能移动到公共目录");
                }
            }

            // 将所有子孙目录设为私有
            for (Integer id : allIds) {
                CatalogDto dto = new CatalogDto();
                dto.setId(id);
                dto.setIsPublic(false);
                catalogMapper.updateById(dto);
            }

            // 将根目录移到目标位置（用 LambdaUpdateWrapper 强制写入 fatherId，支持 null=根目录）
            lambdaUpdate()
                    .eq(CatalogDto::getId, catalogId)
                    .set(CatalogDto::getIsPublic, false)
                    .set(CatalogDto::getFatherId, targetCatalogId)
                    .update();

            // 将所有子孙目录下的文章设为私有
            for (Integer cid : allIds) {
                List<ArticleDto> articles = articleMapper.listByCatalogIdUnfiltered(cid);
                for (ArticleDto a : articles) {
                    ArticleDto update = new ArticleDto();
                    update.setId(a.getId());
                    update.setIsPublic(false);
                    articleMapper.updateById(update);
                }
            }
        } else {
            publishToPublic(catalogId, targetCatalogId);
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CatalogDto copyToMySpace(Integer sourceCatalogId, Integer targetCatalogId) {
        String currentUser = UserContext.getUserName();

        // 1. 获取源目录及其所有子孙目录ID
        List<Integer> allIds = catalogMapper.listDescendantIds(sourceCatalogId);
        allIds.add(sourceCatalogId);

        // 2. 加载所有相关目录记录并构建树
        List<CatalogDto> allCatalogs = catalogMapper.listByIds(allIds);
        List<CatalogDto> sourceTree = buildTree(allCatalogs);
        CatalogDto sourceRoot = sourceTree.stream()
                .filter(c -> c.getId().equals(sourceCatalogId))
                .findFirst().orElse(null);
        if (sourceRoot == null) {
            return null;
        }

        // 3. 加载所有涉及的文章，按 catalogId 分组
        List<ArticleDto> allArticles = new ArrayList<>();
        for (Integer cid : allIds) {
            allArticles.addAll(articleMapper.listByCatalogIdUnfiltered(cid));
        }
        Map<Integer, List<ArticleDto>> articlesByCatalog = allArticles.stream()
                .filter(a -> Objects.nonNull(a.getCatalogId()))
                .collect(Collectors.groupingBy(ArticleDto::getCatalogId));

        // 4. 深度复制
        return deepCopyCatalog(sourceRoot, targetCatalogId, currentUser, articlesByCatalog);
    }

    /** 递归深度复制目录树 */
    private CatalogDto deepCopyCatalog(CatalogDto source, Integer newFatherId, String currentUser,
                                       Map<Integer, List<ArticleDto>> articlesByCatalog) {
        CatalogDto copy = new CatalogDto();
        copy.setName(source.getName());
        copy.setFatherId(newFatherId);
        copy.setOrderId(findMaxOrder(newFatherId) + 1);
        copy.setDescription(source.getDescription());
        copy.setIsPublic(false);
        copy.setCreateBy(currentUser);
        copy.setUpdateBy(currentUser);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        save(copy);

        // 复制文章
        List<ArticleDto> srcArticles = articlesByCatalog.get(source.getId());
        if (CollUtil.isNotEmpty(srcArticles)) {
            for (ArticleDto srcArticle : srcArticles) {
                deepCopyArticle(srcArticle, copy.getId(), currentUser);
            }
        }

        // 递归复制子目录
        if (CollUtil.isNotEmpty(source.getChildren())) {
            for (CatalogDto child : source.getChildren()) {
                deepCopyCatalog(child, copy.getId(), currentUser, articlesByCatalog);
            }
        }
        return copy;
    }

    /** 深度复制单篇文章 */
    private void deepCopyArticle(ArticleDto source, Integer newCatalogId, String currentUser) {
        ArticleDto copy = new ArticleDto();
        copy.setTitle(source.getTitle());
        copy.setAuthor(currentUser);
        copy.setSummary(source.getSummary());
        copy.setCover(source.getCover());
        copy.setCatalogId(newCatalogId);
        copy.setOrderId(articleService.findMaxOrder(newCatalogId) + 1);
        copy.setContentJson(source.getContentJson());
        copy.setContentMd(source.getContentMd());
        copy.setContentText(source.getContentText());
        copy.setIsPublic(false);
        copy.setCreateBy(currentUser);
        copy.setUpdateBy(currentUser);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        articleService.save(copy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishToPublic(Integer catalogId, Integer targetCatalogId) {
        // 1. 收集目录本身及所有子孙目录ID
        List<Integer> catalogIds = catalogMapper.listDescendantIds(catalogId);
        catalogIds.add(catalogId);
        if (targetCatalogId != null) {
            if (catalogIds.contains(targetCatalogId)) {
                throw new BusinessException("目录不能移动到自己的子目录中");
            }
            CatalogDto targetCatalog = catalogMapper.getByIdUnfiltered(targetCatalogId);
            if (targetCatalog == null) {
                throw new BusinessException("目标目录不存在");
            }
            if (!Boolean.TRUE.equals(targetCatalog.getIsPublic())) {
                throw new BusinessException("目标目录不是公共目录");
            }
        }

        // 2. 将所有目录设为公开
        for (Integer id : catalogIds) {
            CatalogDto dto = new CatalogDto();
            dto.setId(id);
            dto.setIsPublic(true);
            catalogMapper.updateById(dto);
        }

        // 3. 将根目录移动到公共空间目标位置
        lambdaUpdate()
                .eq(CatalogDto::getId, catalogId)
                .set(CatalogDto::getFatherId, targetCatalogId)
                .set(CatalogDto::getOrderId, findMaxOrder(targetCatalogId) + 1)
                .set(CatalogDto::getIsPublic, true)
                .update();

        // 4. 将所有子孙目录下的文章设为公开
        for (Integer cid : catalogIds) {
            List<ArticleDto> articles = articleMapper.listByCatalogIdUnfiltered(cid);
            for (ArticleDto a : articles) {
                ArticleDto update = new ArticleDto();
                update.setId(a.getId());
                update.setIsPublic(true);
                articleMapper.updateById(update);
            }
        }
    }
}
