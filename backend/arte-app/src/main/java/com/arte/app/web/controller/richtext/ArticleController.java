package com.arte.app.web.controller.richtext;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.arte.ai.api.BackEndChatService;
import com.arte.ai.pojo.chat.ChatRequestParam;
import com.arte.ai.web.controller.AbstractStreamController;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.api.richtext.ShareService;
import com.arte.app.common.enums.ResourceTypeEnum;
import com.arte.app.common.enums.richtext.ArticleAccessLevelEnum;
import com.arte.app.common.enums.richtext.ArticleTypeEnum;
import com.arte.app.mapper.rbac.RbacRelationMapper;
import com.arte.app.mapper.richtext.ArticleMapper;
import com.arte.app.mapper.richtext.CatalogMapper;
import com.arte.app.mapper.richtext.ShareMapper;
import com.arte.app.pojo.rbac.RoleDto;
import com.arte.app.pojo.richtext.*;
import com.arte.app.pojo.richtext.param.*;
import com.arte.app.service.richtext.DefaultArticleCoverService;
import com.arte.app.service.richtext.PermissionValidator;
import com.arte.app.service.richtext.RichTextFileStorageService;
import com.arte.app.web.websocket.ArticleVersionWebSocketHandler;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.es.EsSearchResponse;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 富文本编辑器控制器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/5 12:37 ✾
 **/
@Slf4j
@RestController
@RequestMapping("/richText/article")
public class ArticleController extends AbstractStreamController {

    private static final int RECENT_ACCESSIBLE_ARTICLE_LIMIT = 40;

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private CatalogMapper catalogMapper;

    @Resource
    private ShareService shareService;

    @Resource
    private ShareMapper shareMapper;

    @Resource
    private RichTextFileStorageService richTextFileStorageService;

    @Resource
    private DefaultArticleCoverService defaultArticleCoverService;

    @Resource
    private PermissionValidator permissionValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private RbacRelationMapper rbacRelationMapper;

    @Resource
    private BackEndChatService backEndChatService;

    @Resource
    private ArticleVersionWebSocketHandler articleVersionWebSocketHandler;

    /**
     * 根据 ID 获取文章
     */
    @AnonymousAccess
    @PostMapping("/getArticleById")
    public ResultContext<ArticleDto> getArticleById(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        // 使用 unfiltered 查询绕过 MyBatis 拦截器的 create_by 条件，
        // 以便访问公共/共享文章（非本人创建）
        ArticleDto result = articleService.getArticleById(article.getId());
        if (result == null) {
            return ResultContext.fail("error.article.notFound");
        }
        result.setEffectivePermission(permissionValidator.assertCanRead(result));
        return ResultContext.success(result);
    }

    /**
     * 为全部未删除文章重新生成向量并写入 Elasticsearch。
     */
    @AnonymousAccess
    @PostMapping("/rebuildEsIndex")
    public ResultContext<ArticleEsReindexResult> rebuildEsIndex() {
        return ResultContext.wrap(articleService::rebuildAllEsIndex);
    }

    /**
     * 新增文章
     */
    @AnonymousAccess
    @PostMapping("/addArticle")
    public ResultContext<ArticleDto> addArticle(@RequestBody ArticleDto article) {
        Assert.notNull(article.getTitle(), MessageUtils.get("error.field.articleTitleRequired"));
        Assert.notNull(article.getCatalogId(), MessageUtils.get("error.field.articleCatalogRequired"));
        CatalogDto parent = catalogMapper.getByIdUnfiltered(article.getCatalogId());
        if (parent == null) {
            throw new BusinessException("error.catalog.parentNotFound");
        }
        permissionValidator.assertCanCreateChild(parent);
        article.setIsPublic(Boolean.TRUE.equals(parent.getIsPublic()));
        String userName = UserContext.getUserOnlineInfo().getUserName();
        article.setAuthor(userName);
        article.setAccessLevel(ArticleAccessLevelEnum.PRIVATE);
        article.setArticleType(ArticleTypeEnum.GENERIC);
        article.setCharacterCount(0);
        article.setRowVersion(1);
        article.setCreateBy(userName);
        article.setUpdateBy(userName);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(article.getCreateTime());
        article.setId(null);
        article.setOrderId(articleService.findMaxOrder(article.getCatalogId()) + 1);
        if (StrUtil.isEmpty(article.getSummary())) {
            article.setSummary(resolveSummary(article.getContentText()));
        }
        ensureArticleCover(article, null);
        boolean save = articleService.save(article);
        if (!save) {
            return ResultContext.fail();
        }
        article.setEffectivePermission(permissionValidator.getEffectivePermission(article));
        // 新建文章时继承所属目录的分享设置
        if (article.getCatalogId() != null) {
            shareService.inheritFromParent(ResourceTypeEnum.CATALOG.getValue(), article.getCatalogId(),
                    ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        }
        // 文章保存后，将引用到的图片设为永久保留
        richTextFileStorageService.makeArticleImagesPermanent(
                article.getContentJson(), article.getContentMd());
        articleService.asyncSaveToEs(article.getId());
        makeCoverPermanent(article.getCover());
        return ResultContext.success(article);
    }

    /**
     * 更新文章
     */
    @AnonymousAccess
    @PostMapping("/updateArticle")
    public ResultContext<Boolean> updateArticle(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        ArticleDto updateParam = new ArticleDto();
        updateParam.setId(article.getId());
        updateParam.setTitle(article.getTitle());
        updateParam.setSummary(article.getSummary());
        if (StrUtil.isEmpty(article.getSummary())) {
            article.setSummary(resolveSummary(article.getContentText()));
        }
        ArticleDto storedArticle = StrUtil.isBlank(article.getCover())
                ? articleMapper.getByIdUnfiltered(article.getId())
                : null;
        ensureArticleCover(article, storedArticle);
        updateParam.setCover(article.getCover());
        if (Objects.nonNull(article.getAccessLevel())) {
            updateParam.setAccessLevel(article.getAccessLevel());
        }
        if (Objects.nonNull(article.getArticleType())) {
            updateParam.setArticleType(article.getArticleType());
        }
        if (Objects.nonNull(article.getCharacterCount())) {
            updateParam.setCharacterCount(article.getCharacterCount());
        }
        updateParam.setContentJson(article.getContentJson());
        updateParam.setContentMd(article.getContentMd());
        updateParam.setContentText(article.getContentText());
        updateParam.setUpdateBy(UserContext.getUserOnlineInfo().getUserName());
        LocalDateTime now = LocalDateTime.now();
        updateParam.setUpdateTime(now);
        ArticleUpdateStatus updateStatus = articleService.updateWithHistory(updateParam);
        if (updateStatus == ArticleUpdateStatus.FAILED) {
            return ResultContext.fail();
        }
        if (updateStatus == ArticleUpdateStatus.UNCHANGED) {
            return ResultContext.success(Boolean.FALSE);
        }
        articleVersionWebSocketHandler.publishVersion(
                updateParam.getId(), updateParam.getRowVersion(), updateParam.getUpdateBy());
        article.setUpdateTime(now);
        // 文章更新后，将引用到的图片设为永久保留
        richTextFileStorageService.makeArticleImagesPermanent(article.getContentJson(), article.getContentMd());
        articleService.asyncSaveToEs(article.getId());
        makeCoverPermanent(article.getCover());
        return ResultContext.success(Boolean.TRUE);
    }

    @AnonymousAccess
    @PostMapping("/listHistory")
    public ResultContext<List<ArticleHistoryPo>> listHistory(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        permissionValidator.assertCanRead(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        return ResultContext.success(articleService.listHistory(article.getId()));
    }

    @AnonymousAccess
    @PostMapping("/getHistoryById")
    public ResultContext<ArticleHistoryPo> getHistoryById(@RequestBody ArticleHistoryPo history) {
        Assert.notNull(history.getId(), MessageUtils.get("error.field.historyIdRequired"));
        ArticleHistoryPo result = articleService.getHistoryById(history.getId());
        Assert.notNull(result, MessageUtils.get("error.field.historyNotFound"));
        permissionValidator.assertCanRead(ResourceTypeEnum.ARTICLE.getValue(), result.getArticleId());
        return ResultContext.success(result);
    }

    /**
     * 根据 ID 获取编辑器所需的文章内容
     */
    @AnonymousAccess
    @PostMapping("/getEditorArticleById")
    public ResultContext<ArticleDto> getEditorArticleById(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        ArticleDto result = articleService.getEditorArticleById(article.getId());
        if (result == null) {
            return ResultContext.fail("error.article.notFound");
        }
        result.setEffectivePermission(permissionValidator.assertCanRead(result));
        return ResultContext.success(result);
    }

    /**
     * 更新文章批注标记。
     * 批注标记保存在 Tiptap JSON 中，拥有可批注权限的用户需要能同步这部分内容。
     */
    @AnonymousAccess
    @PostMapping("/updateArticleCommentMarks")
    public ResultContext<Boolean> updateArticleCommentMarks(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(article.getContentJson(), MessageUtils.get("error.field.articleContentRequired"));
        permissionValidator.assertCanComment(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        if (!permissionValidator.canWrite(ResourceTypeEnum.ARTICLE.getValue(), article.getId())) {
            ArticleDto oldArticle = articleService.getById(article.getId());
            Assert.notNull(oldArticle, MessageUtils.get("error.article.notFound"));
            Assert.isTrue(
                    hasSameContentWithoutCommentMarks(oldArticle.getContentJson(), article.getContentJson()),
                    "仅有批注权限时只能更新批注标记");
        }

        ArticleDto updateParam = new ArticleDto();
        updateParam.setId(article.getId());
        updateParam.setContentJson(article.getContentJson());
        updateParam.setUpdateBy(UserContext.getUserOnlineInfo().getUserName());
        updateParam.setUpdateTime(LocalDateTime.now());

        boolean update = articleService.updateById(updateParam);
        if (!update) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/generateArticleSummary")
    @PreAuthorize("@pcs.check('summary:add')")
    public SseEmitter generateArticleSummary(@RequestBody ChatRequestParam request, HttpServletResponse response) {
        return executeSseStream(backEndChatService.streamGenerate(request), response);
    }

    @PostMapping("/saveArticleSummary")
    @PreAuthorize("@pcs.check('summary:update')")
    public ResultContext<Void> saveArticleSummary(@RequestBody ArticleDto article) {
        Assert.isTrue(article.getId() != null, MessageUtils.get("error.field.articleIdRequired"));
        UpdateWrapper<ArticleDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ArticlePo.COL_ID, article.getId());
        updateWrapper.set(ArticlePo.COL_SUMMARY, article.getSummary());
        updateWrapper.set(ArticlePo.COL_UPDATE_BY, UserContext.getUserOnlineInfo().getUserName());
        updateWrapper.set(ArticlePo.COL_UPDATE_TIME, LocalDateTime.now());
        boolean update = articleService.update(updateWrapper);
        if (update) {
            articleService.asyncSaveArticleDocToEs(articleService.getById(article.getId()));
            return ResultContext.success();
        }
        return ResultContext.fail();
    }

    private boolean hasSameContentWithoutCommentMarks(String oldContentJson, String newContentJson) {
        try {
            JsonNode oldContent = normalizeWithoutCommentMarks(objectMapper.readTree(oldContentJson), null);
            JsonNode newContent = normalizeWithoutCommentMarks(objectMapper.readTree(newContentJson), null);
            return oldContent.equals(newContent);
        } catch (Exception e) {
            log.warn("校验批注标记更新失败", e);
            return false;
        }
    }

    private JsonNode normalizeWithoutCommentMarks(JsonNode node, String fieldName) {
        // 经常因为顺序不一致被校验住
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(normalizeWithoutCommentMarks(item, fieldName)));
            if ("marks".equals(fieldName)) {
                items.stream()
                        .filter(item -> !"comments".equals(item.path("type").asText()))
                        .sorted(Comparator.comparing(JsonNode::toString))
                        .forEach(arrayNode::add);
            } else if ("content".equals(fieldName)) {
                mergeAdjacentTextNodes(items).forEach(arrayNode::add);
            } else {
                items.forEach(arrayNode::add);
            }
            return arrayNode;
        }
        if (!node.isObject()) {
            return node;
        }

        ObjectNode objectNode = objectMapper.createObjectNode();
        Map<String, JsonNode> sortedFields = new TreeMap<>();
        Set<Map.Entry<String, JsonNode>> properties = node.properties();
        properties.forEach(entry -> sortedFields.put(entry.getKey(), entry.getValue()));
        sortedFields.forEach((currentFieldName, value) -> {
            JsonNode normalizedValue = normalizeWithoutCommentMarks(value, currentFieldName);
            if ("marks".equals(currentFieldName) && normalizedValue.isArray() && normalizedValue.isEmpty()) {
                return;
            }
            objectNode.set(currentFieldName, normalizedValue);
        });
        return objectNode;
    }

    private List<JsonNode> mergeAdjacentTextNodes(List<JsonNode> nodes) {
        List<JsonNode> mergedNodes = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!mergedNodes.isEmpty() && canMergeTextNode(mergedNodes.getLast(), node)) {
                JsonNode previous = mergedNodes.removeLast();
                mergedNodes.add(mergeTextNode(previous, node));
            } else {
                mergedNodes.add(node);
            }
        }
        return mergedNodes;
    }

    private boolean canMergeTextNode(JsonNode first, JsonNode second) {
        if (!first.isObject() || !second.isObject()) {
            return false;
        }
        if (!"text".equals(first.path("type").asText()) || !"text".equals(second.path("type").asText())) {
            return false;
        }
        ObjectNode firstComparable = first.deepCopy();
        ObjectNode secondComparable = second.deepCopy();
        firstComparable.remove("text");
        secondComparable.remove("text");
        return firstComparable.equals(secondComparable);
    }

    private JsonNode mergeTextNode(JsonNode first, JsonNode second) {
        ObjectNode mergedNode = first.deepCopy();
        mergedNode.put("text", first.path("text").asText("") + second.path("text").asText(""));
        return mergedNode;
    }

    private void makeCoverPermanent(String cover) {
        richTextFileStorageService.makePermanentByUrl(cover);
    }

    private void ensureArticleCover(ArticleDto article, ArticleDto storedArticle) {
        if (StrUtil.isNotBlank(article.getCover())) {
            return;
        }
        if (storedArticle != null && StrUtil.isNotBlank(storedArticle.getCover())) {
            article.setCover(storedArticle.getCover());
            return;
        }
        article.setCover(defaultArticleCoverService.randomCoverUrl());
    }

    private String resolveSummary(String contentText) {
        if (!hasText(contentText)) {
            return "";
        }
        String normalized = contentText.replaceAll("\\s+", " ").trim();
        return normalized.length() > 50 ? normalized.substring(0, 50) : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 删除文章
     */
    @AnonymousAccess
    @PostMapping("/deleteArticle")
    public ResultContext<Boolean> deleteArticle(@RequestBody ArticleDto article) {
        Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
        permissionValidator.assertCanDelete(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        boolean remove = articleService.removeById(article.getId());
        if (!remove) {
            return ResultContext.fail();
        }
        shareMapper.deleteByResource(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        articleService.asyncDeleteFromEs(article.getId());
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 批量删除文章
     */
    @AnonymousAccess
    @PostMapping("/batchDeleteArticles")
    public ResultContext<Boolean> batchDeleteArticles(@RequestBody List<Integer> ids) {
        for (Integer id : ids) {
            permissionValidator.assertCanDelete(ResourceTypeEnum.ARTICLE.getValue(), id);
        }
        boolean remove = articleService.removeByIds(ids);
        if (!remove) {
            return ResultContext.fail();
        }
        for (Integer id : ids) {
            shareMapper.deleteByResource(ResourceTypeEnum.ARTICLE.getValue(), id);
            articleService.asyncDeleteFromEs(id);
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 分页查询文章列表
     */
    @AnonymousAccess
    @PostMapping("/listArticles")
    public ResultContext<Page<ArticleDto>> listArticles(@RequestBody ArticleParam param) {
        QueryWrapper<ArticleDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(Objects.nonNull(param.getId()), ArticlePo.COL_ID, param.getId());
        queryWrapper.eq(Objects.nonNull(param.getTags()), ArticlePo.COL_CATALOG_ID, param.getTags());
        queryWrapper.like(Objects.nonNull(param.getStartDate()), ArticlePo.COL_TITLE, param.getStartDate());
        queryWrapper.orderByDesc(ArticlePo.COL_UPDATE_TIME);
        Page<ArticleDto> page = articleService.page(param, queryWrapper);
        return ResultContext.success(page);
    }

    /**
     * 获取最近可访问文章
     */
    @AnonymousAccess
    @PostMapping("/listRecentAccessibleArticles")
    public ResultContext<List<ArticleDto>> listRecentAccessibleArticles() {
        String currentUser = UserContext.getUserName();
        List<ArticleDto> result = articleService.listRecentAccessible(
                currentUser, listRoleCodes(currentUser), RECENT_ACCESSIBLE_ARTICLE_LIMIT);
        return ResultContext.success(result);
    }

    private List<String> listRoleCodes(String user) {
        List<RoleDto> roles = rbacRelationMapper.listRolesByUserName(user);
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return roles.stream()
                .map(RoleDto::getRoleCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取指定目录下的文章列表
     */
    @AnonymousAccess
    @PostMapping("/listByCatalog")
    public ResultContext<List<ArticleDto>> listByCatalog(@RequestBody ArticleDto param) {
        Assert.notNull(param.getCatalogId(), MessageUtils.get("error.field.catalogIdRequired"));
        // 先校验目录读权限，再使用 unfiltered 查询绕过 create_by 拦截
        permissionValidator.assertCanRead(ResourceTypeEnum.CATALOG.getValue(), param.getCatalogId());
        List<ArticleDto> list = articleService.listByCatalogIdUnfiltered(param.getCatalogId());
        return ResultContext.success(list);
    }

    /**
     * 移动文章到指定目录
     */
    @AnonymousAccess
    @PostMapping("/moveToCatalog")
    public ResultContext<Boolean> moveToCatalog(@RequestBody ArticleDto param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notNull(param.getCatalogId(), MessageUtils.get("error.field.targetCatalogIdRequired"));
        permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), param.getId());
        permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), param.getCatalogId());
        return ResultContext.wrap(() -> {
            boolean ok = articleService.moveToCatalog(param.getId(), param.getCatalogId());
            if (ok) {
                // 移动后合并新父目录的分享设置（与自身分享取并集，更高权限优先）
                shareService.mergeFromParent(ResourceTypeEnum.ARTICLE.getValue(), param.getId(),
                        ResourceTypeEnum.CATALOG.getValue(), param.getCatalogId());
            }
            return ok;
        });
    }

    /**
     * 更新同目录内的文章顺序
     */
    @AnonymousAccess
    @PostMapping("/reorderArticles")
    public ResultContext<Boolean> reorderArticles(@RequestBody List<ArticleDto> list) {
        Assert.notEmpty(list, MessageUtils.get("error.field.articleListRequired"));
        for (ArticleDto article : list) {
            Assert.notNull(article.getId(), MessageUtils.get("error.field.articleIdRequired"));
            Assert.notNull(article.getOrderId(), MessageUtils.get("error.field.articleOrderRequired"));
            permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), article.getId());
        }
        return ResultContext.wrap(() -> articleService.reorder(list));
    }

    /**
     * 批量移动文章到指定目录
     */
    @AnonymousAccess
    @PostMapping("/batchMoveToCatalogByIds")
    public ResultContext<Boolean> batchMoveToCatalogByIds(@RequestBody ArticleBatchMoveParam param) {
        Assert.notEmpty(param.getArticleIds(), MessageUtils.get("error.field.articleIdListRequired"));
        Assert.notNull(param.getCatalogId(), MessageUtils.get("error.field.targetCatalogIdRequired"));
        permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), param.getCatalogId());
        for (Integer articleId : param.getArticleIds()) {
            permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), articleId);
        }
        return ResultContext.wrap(() -> {
            boolean ok = articleService.batchMoveToCatalog(param.getArticleIds(), param.getCatalogId());
            if (ok) {
                Integer newParentId = param.getCatalogId();
                for (Integer articleId : param.getArticleIds()) {
                    shareService.mergeFromParent(ResourceTypeEnum.ARTICLE.getValue(), articleId,
                            ResourceTypeEnum.CATALOG.getValue(), newParentId);
                }
            }
            return ok;
        });
    }

    /**
     * 获取文章（兼容旧接口，返回一条示例数据）
     */
    @AnonymousAccess
    @PostMapping("/getArticle")
    public ResultContext<ArticleDto> getArticle(@RequestBody ArticleParam param) {
        if (Objects.nonNull(param.getId())) {
            ArticleDto result = articleService.getArticleById(param.getId());
            if (result != null) {
                permissionValidator.assertCanRead(result);
                return ResultContext.success(result);
            }
        }
        return ResultContext.fail("error.article.notFound");
    }

    /**
     * 切换文章公共状态
     */
    @AnonymousAccess
    @PostMapping("/togglePublic")
    public ResultContext<Boolean> togglePublic(@RequestBody PublicToggleParam req) {
        Integer id = req.getId();
        Boolean isPublic = req.getIsPublic();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notNull(isPublic, "isPublic不能为空");
        permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), id);
        if (targetCatalogId != null) {
            permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        }
        articleService.togglePublic(id, isPublic, targetCatalogId);
        return ResultContext.success(true);
    }

    /**
     * 复制文章到我的空间
     */
    @AnonymousAccess
    @PostMapping("/copyToMySpace")
    public ResultContext<ArticleDto> copyToMySpace(@RequestBody CopyToMySpaceParam req) {
        Integer id = req.getId();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notNull(targetCatalogId, MessageUtils.get("error.field.targetCatalogIdRequired"));
        permissionValidator.assertCanRead(ResourceTypeEnum.ARTICLE.getValue(), id);
        permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        ArticleDto result = articleService.copyToMySpace(id, targetCatalogId);
        return ResultContext.success(result);
    }

    /**
     * 发布文章到公共空间（需选择目标目录）
     */
    @AnonymousAccess
    @PostMapping("/publishToPublic")
    public ResultContext<Boolean> publishToPublic(@RequestBody PublishToPublicParam req) {
        Integer id = req.getId();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notNull(targetCatalogId, MessageUtils.get("error.field.targetCatalogIdRequired"));
        permissionValidator.assertCanWrite(ResourceTypeEnum.ARTICLE.getValue(), id);
        permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        articleService.publishToPublic(id, targetCatalogId);
        return ResultContext.success(true);
    }

    @AnonymousAccess
    @PostMapping("/searchArticlesChunks")
    public ResultContext<EsSearchResponse<ChunkDocument>> searchArticlesChunks(@RequestBody ArticleParam articleParam) {
        // todo 目前有可能查出没有阅读权限的文章。先放着以后再说
        return ResultContext.wrap(articleParam, articleService::hybridSearch);
    }
}
