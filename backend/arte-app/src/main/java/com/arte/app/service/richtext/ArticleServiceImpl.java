package com.arte.app.service.richtext;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.BooleanUtil;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.base.TagRelationService;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.common.constant.RichTextRedisScript;
import com.arte.app.common.enums.richtext.ArticleAccessLevelEnum;
import com.arte.app.manager.ArticleRepository;
import com.arte.app.manager.ChunkRepository;
import com.arte.app.mapper.richtext.ArticleHistoryMapper;
import com.arte.app.mapper.richtext.ArticleMapper;
import com.arte.app.mapper.richtext.CatalogMapper;
import com.arte.app.pojo.base.TagRelationDto;
import com.arte.app.pojo.richtext.*;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.app.strategy.richtext.handler.HybridSearchStrategy;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.es.EsSearchResponse;
import com.arte.core.exception.ArticleException;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 文章服务实现
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Slf4j
@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, ArticleDto> implements ArticleService {

    private final AtomicBoolean rebuildingEsIndex = new AtomicBoolean(false);

    @Resource
    private CatalogMapper catalogMapper;
    @Resource
    private ArticleRepository articleRepository;
    @Resource
    private ChunkRepository chunkRepository;
    @Resource
    private TiptapJsonParser tiptapJsonParser;
    @Resource
    private HybridSearchStrategy hybridSearchStrategy;
    @Resource
    private TagRelationService tagRelationService;
    @Resource
    private ArticleHistoryMapper articleHistoryMapper;
    @Resource
    private RedissonClient redissonClient;

    @Value("${rich-text.history.max-versions:20}")
    private int historyMaxVersions;

    @Value("${rich-text.es-sync.delay-ms:300000}")
    private long esSyncDelayMs;

    @Value("${rich-text.es-sync.retry-delay-ms:60000}")
    private long esSyncRetryDelayMs;

    @Value("${rich-text.es-sync.batch-size:100}")
    private int esSyncBatchSize;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleUpdateStatus updateWithHistory(ArticleDto article) {
        ArticleDto current = baseMapper.getByIdWithContentForUpdate(article.getId());
        if (current == null) {
            throw new BusinessException("error.article.notFound");
        }

        String nextTitle = article.getTitle() == null ? current.getTitle() : article.getTitle();
        String nextContent = article.getContentText() == null ? current.getContentText() : article.getContentText();
        boolean versionChanged = !Objects.equals(current.getTitle(), nextTitle)
                || !Objects.equals(current.getContentText(), nextContent);
        if (!hasPersistentChanges(current, article)) {
            return ArticleUpdateStatus.UNCHANGED;
        }

        int currentVersion = resolveCurrentVersion(current);
        if (versionChanged) {
            ArticleHistoryPo snapshot = new ArticleHistoryPo()
                    .setArticleId(current.getId())
                    .setVersionNo(currentVersion)
                    .setTitle(current.getTitle())
                    .setContent(current.getContentText())
                    .setModifiedBy(current.getUpdateBy())
                    .setModifiedTime(current.getUpdateTime());
            articleHistoryMapper.insert(snapshot);
            article.setRowVersion(currentVersion + 1);
        } else {
            article.setRowVersion(currentVersion);
        }

        boolean updated = updateById(article);
        if (updated && versionChanged) {
            trimHistory(article.getId());
        }
        return updated ? ArticleUpdateStatus.UPDATED : ArticleUpdateStatus.FAILED;
    }

    private boolean hasPersistentChanges(ArticleDto current, ArticleDto incoming) {
        return changed(current.getTitle(), incoming.getTitle())
                || changed(current.getSummary(), incoming.getSummary())
                || changed(current.getCover(), incoming.getCover())
                || changed(current.getAccessLevel(), incoming.getAccessLevel())
                || changed(current.getArticleType(), incoming.getArticleType())
                || changed(current.getCharacterCount(), incoming.getCharacterCount())
                || changed(current.getContentMd(), incoming.getContentMd())
                || changed(current.getContentText(), incoming.getContentText());
    }

    private boolean changed(Object current, Object incoming) {
        return incoming != null && !Objects.equals(current, incoming);
    }

    private int resolveCurrentVersion(ArticleDto current) {
        int rowVersion = current.getRowVersion() == null ? 1 : current.getRowVersion();
        QueryWrapper<ArticleHistoryPo> query = new QueryWrapper<>();
        query.eq(ArticleHistoryPo.COL_ARTICLE_ID, current.getId());
        query.orderByDesc(ArticleHistoryPo.COL_VERSION_NO);
        query.last("limit 1");
        ArticleHistoryPo latestHistory = articleHistoryMapper.selectOne(query);
        if (latestHistory == null || latestHistory.getVersionNo() == null) {
            return rowVersion;
        }
        return Math.max(rowVersion, latestHistory.getVersionNo() + 1);
    }

    @Override
    public List<ArticleHistoryPo> listHistory(Integer articleId) {
        QueryWrapper<ArticleHistoryPo> query = new QueryWrapper<>();
        query.select("id", "article_id", "version_no", "title", "modified_by", "modified_time");
        query.eq(ArticleHistoryPo.COL_ARTICLE_ID, articleId);
        query.orderByDesc(ArticleHistoryPo.COL_VERSION_NO);
        return articleHistoryMapper.selectList(query);
    }

    @Override
    public ArticleHistoryPo getHistoryById(Long historyId) {
        return articleHistoryMapper.selectById(historyId);
    }

    @Override
    public List<ArticleDto> listRecentAccessible(String currentUser, List<String> targetRoles, Integer limit) {
        List<ArticleDto> articles = baseMapper.listRecentAccessible(currentUser, targetRoles, limit);
        if (CollUtil.isEmpty(articles)) {
            return articles;
        }
        Map<Integer, String> permissionMap = baseMapper.listEffectivePermissions(
                        articles.stream().map(ArticleDto::getId).toList(), currentUser, targetRoles).stream()
                .collect(Collectors.toMap(ArticleDto::getId, ArticleDto::getEffectivePermission));
        articles.forEach(article -> article.setEffectivePermission(permissionMap.get(article.getId())));
        return articles;
    }

    @Override
    public List<ArticleDto> listByCatalogIdUnfiltered(Integer catalogId) {
        return baseMapper.listByCatalogIdUnfiltered(catalogId);
    }

    private void trimHistory(Integer articleId) {
        int limit = Math.max(1, historyMaxVersions);
        QueryWrapper<ArticleHistoryPo> query = new QueryWrapper<>();
        query.eq(ArticleHistoryPo.COL_ARTICLE_ID, articleId);
        query.orderByDesc(ArticleHistoryPo.COL_VERSION_NO);
        List<ArticleHistoryPo> snapshots = articleHistoryMapper.selectList(query);
        if (snapshots.size() <= limit) {
            return;
        }
        List<Long> obsoleteIds = snapshots.subList(limit, snapshots.size()).stream()
                .map(ArticleHistoryPo::getId)
                .toList();
        articleHistoryMapper.deleteByIds(obsoleteIds);
    }

    @Override
    public void saveToEs(Integer articleId) {
        try {
            // 获取文章元数据信息
            ArticleDto article = getCompleteArticle(articleId);
            if (article == null) {
                deleteArticleFromEs(articleId);
                return;
            }
            // 保存并返回文章 ES 文档
            ArticleDocument articleDocument = saveArticleDocToEs(article);
            // 将文章内容解析为 ES 分块文档列表
            List<ChunkDocument> articleChunks = tiptapJsonParser.parse(article.getContentJson(), article.getId(), articleDocument);
            // 摘要不保存到分块中
            articleDocument.setSummary(CharSequenceUtil.EMPTY);
            // 先删除再插入
            DeleteByQueryResponse deleteByQueryResponse = chunkRepository.deleteByArticleId(article.getId());
            if (!deleteByQueryResponse.failures().isEmpty()) {
                log.error("文章分块删除失败，文章 ID：{}，失败原因：{}", article.getId(), deleteByQueryResponse.failures());
                throw new ArticleException(ResultCodeEnum.DELETE_EXCEPTION, MessageUtils.get("error.article.esChunkDeleteFailed", article.getId()));
            }
            if (CollUtil.isNotEmpty(articleChunks)) {
                BulkResponse bulkResponse = chunkRepository.saveBatch(articleChunks);
                if (bulkResponse.errors()) {
                    long failureCount = bulkResponse.items().stream()
                            .filter(item -> item.error() != null)
                            .count();
                    throw new ArticleException(ResultCodeEnum.ADD_EXCEPTION,
                            "文章 ID：" + articleId + "，ES 索引分块保存失败，失败数量：" + failureCount);
                }
            }
        } catch (Exception e) {
            log.error("保存文章到 elasticsearch 异常", e);
            throw new ArticleException(ResultCodeEnum.ADD_EXCEPTION, MessageUtils.get("error.article.esSaveFailed", articleId));
        }
    }

    @Override
    public void asyncSaveToEs(Integer articleId) {
        if (articleId == null) {
            log.warn("忽略文章 ES 同步任务：文章 ID 为空");
            return;
        }
        long dueAt = System.currentTimeMillis() + esSyncDelayMs;
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                RichTextRedisScript.MARK_ES_SYNC_DIRTY_SCRIPT,
                RScript.ReturnType.LONG,
                List.of(getDirtyVersionKey(articleId), RichTextRedisScript.ES_SYNC_PENDING_KEY),
                Long.toString(dueAt),
                articleId.toString());
        log.debug("文章 ES 同步任务已标记，articleId={}，dueAt={}", articleId, dueAt);
    }

    @Override
    public void asyncDeleteFromEs(Integer articleId) {
        long dueAt = System.currentTimeMillis();
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                RichTextRedisScript.MARK_ES_DELETE_DIRTY_SCRIPT,
                RScript.ReturnType.LONG,
                List.of(getDirtyVersionKey(articleId), RichTextRedisScript.ES_SYNC_PENDING_KEY),
                Long.toString(dueAt),
                articleId.toString());
        log.debug("文章 ES 删除任务已标记，articleId={}", articleId);
    }

    private void deleteArticleFromEs(Integer articleId) {
        DeleteResponse deleteResponse = articleRepository.delete(articleId);
        if (Objects.nonNull(deleteResponse.failureStore())) {
            throw new ArticleException(ResultCodeEnum.DELETE_EXCEPTION,
                    "文章 ID：" + articleId + "，ES 索引删除失败");
        }
        DeleteByQueryResponse deleteByQueryResponse = chunkRepository.deleteByArticleId(articleId);
        if (!deleteByQueryResponse.failures().isEmpty()) {
            throw new ArticleException(ResultCodeEnum.DELETE_EXCEPTION,
                    "文章 ID：" + articleId + "，ES 索引分块删除失败");
        }
    }

    /**
     * 消费到期的文章 ES 同步任务。所有应用实例都可以扫描，文章级分布式锁保证不会重复执行。
     */
    @Scheduled(fixedDelayString = "${rich-text.es-sync.scan-interval-ms:10000}")
    public void processPendingEsSyncTasks() {
        long now = System.currentTimeMillis();
        // 找出到期的文章 ID 列表
        List<String> articleIds = findDueEsSyncTasks(now);
        for (String articleIdValue : articleIds) {
            try {
                // 处理到期的文章，同步到 ES
                processPendingEsSyncTask(Integer.valueOf(articleIdValue), now);
            } catch (NumberFormatException e) {
                log.error("Redis 中存在非法文章 ES 同步任务，articleId={}", articleIdValue, e);
                removeInvalidEsSyncTask(articleIdValue);
            } catch (Exception e) {
                log.error("消费文章 ES 同步任务异常，articleId={}", articleIdValue, e);
            }
        }
    }

    private List<String> findDueEsSyncTasks(long now) {
        List<String> articleIds = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_ONLY,
                RichTextRedisScript.FIND_DUE_ES_SYNC_TASKS_SCRIPT,
                RScript.ReturnType.LIST,
                List.of(RichTextRedisScript.ES_SYNC_PENDING_KEY),
                Long.toString(now),
                Integer.toString(Math.max(1, esSyncBatchSize)));
        return articleIds == null ? List.of() : articleIds;
    }

    private void processPendingEsSyncTask(Integer articleId, long now) {
        RLock lock = redissonClient.getLock(RichTextRedisScript.ES_SYNC_LOCK_KEY_PREFIX + articleId);
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (!isDueEsSyncTask(articleId, now)) {
                return;
            }

            long dirtyVersion = getDirtyVersion(articleId);
            try {
                saveToEs(articleId);
                completeEsSyncTask(articleId, dirtyVersion);
                log.debug("文章 ES 同步完成，articleId={}，dirtyVersion={}", articleId, dirtyVersion);
            } catch (Exception e) {
                rescheduleEsSyncTask(articleId, esSyncRetryDelayMs);
                log.error("文章 ES 同步失败，已重新入队，articleId={}，retryDelayMs={}",
                        articleId, esSyncRetryDelayMs, e);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean isDueEsSyncTask(Integer articleId, long now) {
        Long due = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                RichTextRedisScript.IS_DUE_ES_SYNC_TASK_SCRIPT,
                RScript.ReturnType.LONG,
                Collections.singletonList(RichTextRedisScript.ES_SYNC_PENDING_KEY),
                articleId.toString(),
                Long.toString(now));
        return due != null && due > 0;
    }

    private long getDirtyVersion(Integer articleId) {
        return redissonClient.getAtomicLong(getDirtyVersionKey(articleId)).get();
    }

    private void completeEsSyncTask(Integer articleId, long processedVersion) {
        long dueAt = System.currentTimeMillis() + esSyncDelayMs;
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                RichTextRedisScript.COMPLETE_ES_SYNC_TASK_SCRIPT,
                RScript.ReturnType.LONG,
                List.of(getDirtyVersionKey(articleId), RichTextRedisScript.ES_SYNC_PENDING_KEY),
                Long.toString(processedVersion),
                Long.toString(dueAt),
                articleId.toString());
    }

    private void rescheduleEsSyncTask(Integer articleId, long delayMs) {
        long dueAt = System.currentTimeMillis() + Math.max(0, delayMs);
        redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                RichTextRedisScript.RESCHEDULE_ES_SYNC_TASK_SCRIPT,
                RScript.ReturnType.LONG,
                Collections.singletonList(RichTextRedisScript.ES_SYNC_PENDING_KEY),
                Long.toString(dueAt),
                articleId.toString());
    }

    private void removeInvalidEsSyncTask(String articleId) {
        redissonClient.getScoredSortedSet(RichTextRedisScript.ES_SYNC_PENDING_KEY, StringCodec.INSTANCE)
                .remove(articleId);
    }

    private String getDirtyVersionKey(Integer articleId) {
        return RichTextRedisScript.ES_SYNC_DIRTY_VERSION_KEY_PREFIX + articleId;
    }

    @Override
    public ArticleDocument saveArticleDocToEs(ArticleDto article) {
        // article 文档和 chunk 文块文档都先生成好了再保存，避免 chunk 文块文档生成出错时，只保存了 article 文档
        ArticleDocument articleDocument = article.toArticleDocument();
        // 先删除再插入
        DeleteResponse deleteResponse = articleRepository.delete(article.getId());
        if (Objects.nonNull(deleteResponse.failureStore())) {
            log.error("文章删除失败，文章 ID：{}，失败原因：{}", article.getId(), deleteResponse.failureStore());
            throw new ArticleException(ResultCodeEnum.DELETE_EXCEPTION, MessageUtils.get("error.article.esDeleteFailed", article.getId()));
        }
        articleRepository.save(articleDocument);
        return articleDocument;
    }

    @Override
    public void asyncSaveArticleDocToEs(ArticleDto article) {
        if (article == null) {
            log.warn("忽略文章 ES 文档同步任务：文章为空");
            return;
        }
        asyncSaveToEs(article.getId());
    }

    @Override
    public ArticleEsReindexResult rebuildAllEsIndex() {
        if (!rebuildingEsIndex.compareAndSet(false, true)) {
            throw new BusinessException("error.article.esRebuilding");
        }
        try {
            List<ArticleDto> articles = baseMapper.listAllWithContentUnfiltered();
            int succeeded = 0;
            int failed = 0;
            for (ArticleDto article : articles) {
                try {
                    saveToEs(article.getId());
                    succeeded++;
                } catch (Exception e) {
                    failed++;
                    log.error("全量重建文章 ES 索引失败，articleId={}", article.getId(), e);
                }
            }
            log.info("文章 ES 索引全量重建完成，total={}, succeeded={}, failed={}",
                    articles.size(), succeeded, failed);
            return new ArticleEsReindexResult(articles.size(), succeeded, failed);
        } finally {
            rebuildingEsIndex.set(false);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean moveToCatalog(Integer articleId, Integer catalogId) {
        CatalogDto targetCatalog = getTargetCatalog(catalogId);
        ArticleDto article = new ArticleDto();
        article.setId(articleId);
        article.setCatalogId(catalogId);
        article.setOrderId(findMaxOrder(catalogId) + 1);
        article.setIsPublic(Boolean.TRUE.equals(targetCatalog.getIsPublic()));
        article.setUpdateBy(UserContext.getUserName());
        article.setUpdateTime(LocalDateTime.now());
        return updateById(article);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reorder(List<ArticleDto> list) {
        return updateBatchById(list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean batchMoveToCatalog(List<Integer> articleIds, Integer catalogId) {
        if (CollUtil.isEmpty(articleIds)) {
            return false;
        }
        CatalogDto targetCatalog = getTargetCatalog(catalogId);
        boolean targetPublic = Boolean.TRUE.equals(targetCatalog.getIsPublic());
        LocalDateTime now = LocalDateTime.now();
        String currentUser = UserContext.getUserName();
        final int[] nextOrder = {findMaxOrder(catalogId) + 1};
        List<ArticleDto> list = articleIds.stream().map(id -> {
            ArticleDto article = new ArticleDto();
            article.setId(id);
            article.setCatalogId(catalogId);
            article.setOrderId(nextOrder[0]++);
            article.setIsPublic(targetPublic);
            article.setUpdateBy(currentUser);
            article.setUpdateTime(now);
            return article;
        }).toList();
        return updateBatchById(list);
    }

    @Override
    public Integer findMaxOrder(Integer catalogId) {
        return baseMapper.findMaxOrder(catalogId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void togglePublic(Integer articleId, boolean isPublic, Integer targetCatalogId) {
        if (!isPublic) {
            if (targetCatalogId == null) {
                throw new BusinessException("error.article.withdrawNeedsPrivateCatalog");
            }
            CatalogDto targetCatalog = getTargetCatalog(targetCatalogId);
            if (Boolean.TRUE.equals(targetCatalog.getIsPublic())) {
                throw new BusinessException("error.article.withdrawNoPublicCatalog");
            }
            // 撤回公共状态时，用 LambdaUpdateWrapper 强制写入 catalogId（支持 null=根目录）
            lambdaUpdate()
                    .eq(ArticleDto::getId, articleId)
                    .set(ArticleDto::getIsPublic, false)
                    .set(ArticleDto::getCatalogId, targetCatalogId)
                    .set(ArticleDto::getUpdateBy, UserContext.getUserName())
                    .set(ArticleDto::getUpdateTime, LocalDateTime.now())
                    .update();
            return;
        }
        if (targetCatalogId == null) {
            throw new BusinessException("error.article.publishNeedsPublicCatalog");
        }
        publishToPublic(articleId, targetCatalogId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleDto copyToMySpace(Integer sourceArticleId, Integer targetCatalogId) {
        ArticleDto source = this.getCompleteArticle(sourceArticleId);
        if (source == null) {
            return null;
        }
        String currentUser = UserContext.getUserName();
        ArticleDto copy = new ArticleDto();
        copy.setTitle(source.getTitle());
        copy.setAuthor(currentUser);
        copy.setSummary(source.getSummary());
        copy.setCover(source.getCover());
        copy.setCatalogId(targetCatalogId);
        copy.setOrderId(findMaxOrder(targetCatalogId) + 1);
        copy.setContentJson(source.getContentJson());
        copy.setContentMd(source.getContentMd());
        copy.setContentText(source.getContentText());
        copy.setIsPublic(false);
        copy.setCreateBy(currentUser);
        copy.setUpdateBy(currentUser);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        copy.setCreateTime(now);
        copy.setUpdateTime(now);
        save(copy);
        return copy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishToPublic(Integer articleId, Integer targetCatalogId) {
        CatalogDto targetCatalog = getTargetCatalog(targetCatalogId);
        if (!Boolean.TRUE.equals(targetCatalog.getIsPublic())) {
            throw new BusinessException("error.catalog.targetNotPublic");
        }
        ArticleDto article = new ArticleDto();
        article.setId(articleId);
        article.setCatalogId(targetCatalogId);
        article.setOrderId(findMaxOrder(targetCatalogId) + 1);
        article.setIsPublic(true);
        article.setUpdateBy(UserContext.getUserName());
        article.setUpdateTime(LocalDateTime.now());
        updateById(article);
    }

    @Override
    public EsSearchResponse<ChunkDocument> hybridSearch(ArticleParam param) {
        ArticleEsSearchRequest request = buildEsRequest(param);
        return hybridSearchStrategy.search(request);
    }

    @Override
    public ArticleDto getCompleteArticle(Integer articleId) {
        ArticleDto article = getById(articleId);
        if (article == null) {
            return null;
        }
        // 填充文章标签 ID 列表
        List<TagRelationDto> tagRelations = tagRelationService.listTagIds(List.of(articleId));
        if (CollUtil.isNotEmpty(tagRelations)) {
            article.setTags(tagRelations.stream().map(TagRelationDto::getTagId).collect(Collectors.toSet()));
        }
        return article;
    }

    @Override
    public ArticleDto getArticleById(Integer articleId) {
        return baseMapper.getByIdWithContentUnfiltered(articleId);
    }

    @Override
    public ArticleDto getEditorArticleById(Integer articleId) {
        return baseMapper.getEditorArticleById(articleId);
    }

    /**
     * 将查询参数转换为 HybridSearchRequest 请求
     *
     * @param param 文章查询参数
     * @return ES 混合查询请求
     */
    private ArticleEsSearchRequest buildEsRequest(ArticleParam param) {
        ArticleEsSearchRequest.Builder builder = ArticleEsSearchRequest.builder();
        if (BooleanUtil.isTrue(param.getSearchTitle())) {
            builder.titleQuery(param.getSearchBingoText());
        } else {
            builder.textQuery(param.getSearchBingoText());
        }
        builder.page(Math.max(0, param.getEsCurrent()))
                .size(param.getEsSize() > 0 ? param.getEsSize() : 20)
                // 第一层：硬过滤
                .hardFilters(buildHardFilters(param))
                // 第二层：should 选项（phraseQuery 使用 textQuery，标题权重 3x）
                .shouldOptions(new ArticleEsSearchRequest.ShouldOptions(3.0f, 1.0f, param.getSearchBingoText(), 2, 1));
        if (BooleanUtil.isTrue(param.getSemanticSearch())) {
            // 第三层：kNN 内部过滤
            builder.knnFilters(buildKnnFilters(param));
        }
        return builder.build();
    }

    /**
     * 构建 HybridSearchRequest 顶层字段之外的额外过滤条件
     *
     * @param param 文章查询参数
     * @return 第一层硬过滤条件，不参与评分
     */
    private ArticleEsSearchRequest.HardFilters buildHardFilters(ArticleParam param) {
        Boolean isPublic = param.getAccessLevel() != null ? ArticleAccessLevelEnum.PUBLIC.equals(param.getAccessLevel()) : null;
        return new ArticleEsSearchRequest.HardFilters(
                param.getAuthors(), param.getArticleIds(), param.getCatalogIds(), param.getTags(), isPublic,
                param.getCharacterCountFloor(), param.getCharacterCountCeil(),
                param.getCreateTimeFloor(), param.getCreateTimeCeil(),
                param.getUpdateTimeFloor(), param.getUpdateTimeCeil(),
                Map.of());
    }

    /**
     * 构建 KNN 中的过滤条件，拒绝出现查苏轼召回李白的情形
     *
     * @param param 文章查询参数
     * @return KNN 中的过滤条件
     */
    private ArticleEsSearchRequest.KnnFilters buildKnnFilters(ArticleParam param) {
        String articleType = param.getArticleType() != null ? param.getArticleType().getValue() : null;
        return new ArticleEsSearchRequest.KnnFilters(param.getSemanticSearch(), param.getAuthors(), param.getArticleIds(),
                param.getCatalogIds(), param.getTags(), articleType, null, param.getSemanticSearch(), Map.of());
    }

    private CatalogDto getTargetCatalog(Integer catalogId) {
        if (catalogId == null) {
            throw new BusinessException("error.catalog.targetRequired");
        }
        CatalogDto targetCatalog = catalogMapper.getByIdUnfiltered(catalogId);
        if (targetCatalog == null) {
            throw new BusinessException("error.catalog.targetNotFound");
        }
        return targetCatalog;
    }

}
