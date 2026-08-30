package com.arte.app.service.richtext;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.richtext.ArticleCommentThreadService;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.mapper.richtext.ArticleCommentMapper;
import com.arte.app.mapper.richtext.ArticleCommentThreadMapper;
import com.arte.app.pojo.richtext.*;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章批注线程服务实现
 *
 * @author Codex
 * @since 2026/6/20
 */
@Service
public class ArticleCommentThreadServiceImpl
        extends ServiceImpl<ArticleCommentThreadMapper, ArticleCommentThreadDto>
        implements ArticleCommentThreadService {

    @Resource
    private ArticleCommentMapper articleCommentMapper;

    @Resource
    private ArticleService articleService;

    @Resource
    private PermissionValidator permissionValidator;

    @Override
    public List<ArticleCommentThreadDto> listThreads(Integer articleId) {
        assertCanComment(articleId);
        return baseMapper.listThreadsWithComments(articleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleCommentThreadDto createThread(ArticleCommentThreadDto param) {
        Assert.notNull(param.getArticleId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(param.getContent(), MessageUtils.get("error.field.annotateContentRequired"));
        assertCanComment(param.getArticleId());

        LocalDateTime now = LocalDateTime.now();
        String currentUser = UserContext.getUserName();
        String threadId = StrUtil.blankToDefault(param.getThreadId(), createThreadId());

        ArticleCommentThreadDto thread = new ArticleCommentThreadDto();
        thread.setArticleId(param.getArticleId());
        thread.setThreadId(threadId);
        thread.setIsDelete(false);
        thread.setCreateBy(currentUser);
        thread.setUpdateBy(currentUser);
        thread.setCreateTime(now);
        thread.setUpdateTime(now);
        save(thread);

        ArticleCommentDto firstComment = new ArticleCommentDto();
        firstComment.setArticleId(param.getArticleId());
        firstComment.setThreadId(threadId);
        firstComment.setContent(param.getContent());
        fillCreateComment(firstComment, now, currentUser);
        articleCommentMapper.insert(firstComment);

        thread.setComments(List.of(firstComment));
        return thread;
    }

    @Override
    public ArticleCommentDto addComment(ArticleCommentDto param) {
        Assert.notNull(param.getArticleId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(param.getThreadId(), MessageUtils.get("error.field.threadIdRequired"));
        Assert.notBlank(param.getContent(), MessageUtils.get("error.field.commentContentRequired"));
        assertCanComment(param.getArticleId());
        assertThreadExists(param.getArticleId(), param.getThreadId());

        LocalDateTime now = LocalDateTime.now();
        String currentUser = UserContext.getUserName();
        ArticleCommentDto comment = new ArticleCommentDto();
        comment.setArticleId(param.getArticleId());
        comment.setThreadId(param.getThreadId());
        comment.setContent(param.getContent());
        fillCreateComment(comment, now, currentUser);
        articleCommentMapper.insert(comment);
        touchThread(param.getArticleId(), param.getThreadId(), now, currentUser);
        return comment;
    }

    @Override
    public Boolean updateComment(ArticleCommentDto param) {
        Assert.notNull(param.getArticleId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(param.getThreadId(), MessageUtils.get("error.field.threadIdRequired"));
        Assert.notBlank(param.getCommentId(), MessageUtils.get("error.field.commentIdRequired"));
        Assert.notBlank(param.getContent(), MessageUtils.get("error.field.commentContentRequired"));
        assertCanComment(param.getArticleId());

        LocalDateTime now = LocalDateTime.now();
        String currentUser = UserContext.getUserName();
        assertThreadOpen(param.getArticleId(), param.getThreadId());
        ArticleCommentDto comment = getComment(param.getArticleId(), param.getThreadId(), param.getCommentId());
        assertCommentOwner(comment, currentUser);
        UpdateWrapper<ArticleCommentDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ArticleCommentPo.COL_ARTICLE_ID, param.getArticleId());
        updateWrapper.eq(ArticleCommentPo.COL_THREAD_ID, param.getThreadId());
        updateWrapper.eq(ArticleCommentPo.COL_COMMENT_ID, param.getCommentId());
        updateWrapper.set(ArticleCommentPo.COL_CONTENT, param.getContent());
        updateWrapper.set(ArticleCommentPo.COL_UPDATE_BY, currentUser);
        updateWrapper.set(ArticleCommentPo.COL_UPDATE_TIME, now);
        int count = articleCommentMapper.update(updateWrapper);
        if (count <= 0) {
            throw new BusinessException("error.comment.notFound");
        }
        touchThread(param.getArticleId(), param.getThreadId(), now, currentUser);
        return true;
    }

    @Override
    public Boolean deleteComment(ArticleCommentDto param) {
        Assert.notNull(param.getArticleId(), MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(param.getThreadId(), MessageUtils.get("error.field.threadIdRequired"));
        Assert.notBlank(param.getCommentId(), MessageUtils.get("error.field.commentIdRequired"));
        assertCanComment(param.getArticleId());

        LocalDateTime now = LocalDateTime.now();
        String currentUser = UserContext.getUserName();
        assertThreadOpen(param.getArticleId(), param.getThreadId());
        ArticleCommentDto comment = getComment(param.getArticleId(), param.getThreadId(), param.getCommentId());
        assertCanDeleteComment(param.getArticleId(), comment, currentUser);
        UpdateWrapper<ArticleCommentDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ArticleCommentPo.COL_ARTICLE_ID, param.getArticleId());
        updateWrapper.eq(ArticleCommentPo.COL_THREAD_ID, param.getThreadId());
        updateWrapper.eq(ArticleCommentPo.COL_COMMENT_ID, param.getCommentId());
        updateWrapper.set(ArticleCommentPo.COL_DELETED_AT, now);
        updateWrapper.set(ArticleCommentPo.COL_UPDATE_BY, currentUser);
        updateWrapper.set(ArticleCommentPo.COL_UPDATE_TIME, now);
        if (Boolean.TRUE.equals(param.getDeleteContent())) {
            updateWrapper.set(ArticleCommentPo.COL_CONTENT, "");
        }
        int count = articleCommentMapper.update(updateWrapper);
        if (count <= 0) {
            throw new BusinessException("error.comment.notFound");
        }
        touchThread(param.getArticleId(), param.getThreadId(), now, currentUser);
        return true;
    }

    @Override
    public Boolean resolveThread(Integer articleId, String threadId) {
        Assert.notNull(articleId, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(threadId, MessageUtils.get("error.field.threadIdRequired"));
        assertCanComment(articleId);
        LocalDateTime now = LocalDateTime.now();
        return updateThread(articleId, threadId, now, UserContext.getUserName(), true);
    }

    @Override
    public Boolean unresolveThread(Integer articleId, String threadId) {
        Assert.notNull(articleId, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(threadId, MessageUtils.get("error.field.threadIdRequired"));
        assertCanComment(articleId);
        LocalDateTime now = LocalDateTime.now();
        return updateThread(articleId, threadId, now, UserContext.getUserName(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteThread(Integer articleId, String threadId) {
        Assert.notNull(articleId, MessageUtils.get("error.field.articleIdRequired"));
        Assert.notBlank(threadId, MessageUtils.get("error.field.threadIdRequired"));
        assertCanComment(articleId);
        String currentUser = UserContext.getUserName();
        ArticleCommentThreadDto thread = getThread(articleId, threadId);
        assertThreadOpen(thread);
        assertCanDeleteThread(articleId, thread, currentUser);

        QueryWrapper<ArticleCommentThreadDto> threadQuery = new QueryWrapper<>();
        threadQuery.eq(ArticleCommentThreadPo.COL_ARTICLE_ID, articleId);
        threadQuery.eq(ArticleCommentThreadPo.COL_THREAD_ID, threadId);
        boolean removed = remove(threadQuery);
        if (!removed) {
            throw new BusinessException("error.comment.threadNotFound");
        }

        QueryWrapper<ArticleCommentDto> commentQuery = new QueryWrapper<>();
        commentQuery.eq(ArticleCommentPo.COL_ARTICLE_ID, articleId);
        commentQuery.eq(ArticleCommentPo.COL_THREAD_ID, threadId);
        articleCommentMapper.delete(commentQuery);
        return true;
    }

    private void assertCanComment(Integer articleId) {
        permissionValidator.assertCanComment(articleId);
    }

    private void assertThreadExists(Integer articleId, String threadId) {
        getThread(articleId, threadId);
    }

    private ArticleCommentThreadDto getThread(Integer articleId, String threadId) {
        QueryWrapper<ArticleCommentThreadDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ArticleCommentThreadPo.COL_ARTICLE_ID, articleId);
        queryWrapper.eq(ArticleCommentThreadPo.COL_THREAD_ID, threadId);
        ArticleCommentThreadDto thread = getOne(queryWrapper, false);
        if (thread == null) {
            throw new BusinessException("error.comment.threadNotFound");
        }
        return thread;
    }

    private ArticleCommentDto getComment(Integer articleId, String threadId, String commentId) {
        QueryWrapper<ArticleCommentDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ArticleCommentPo.COL_ARTICLE_ID, articleId);
        queryWrapper.eq(ArticleCommentPo.COL_THREAD_ID, threadId);
        queryWrapper.eq(ArticleCommentPo.COL_COMMENT_ID, commentId);
        ArticleCommentDto comment = articleCommentMapper.selectOne(queryWrapper);
        if (comment == null) {
            throw new BusinessException("error.comment.notFound");
        }
        return comment;
    }

    private void assertCommentOwner(ArticleCommentDto comment, String currentUser) {
        if (!isCreatedBy(comment.getCreateBy(), currentUser)) {
            throw new BusinessException("error.comment.onlyEditOwn");
        }
    }

    private void assertThreadOpen(Integer articleId, String threadId) {
        assertThreadOpen(getThread(articleId, threadId));
    }

    private void assertThreadOpen(ArticleCommentThreadDto thread) {
        if (thread.getResolvedAt() != null) {
            throw new BusinessException("error.comment.onlyReopenResolved");
        }
    }

    private void assertCanDeleteComment(Integer articleId, ArticleCommentDto comment, String currentUser) {
        if (!isCreatedBy(comment.getCreateBy(), currentUser) && !isArticleAuthor(articleId, currentUser)) {
            throw new BusinessException("error.comment.onlyDeleteOwn");
        }
    }

    private void assertCanDeleteThread(Integer articleId, ArticleCommentThreadDto thread, String currentUser) {
        if (!isCreatedBy(thread.getCreateBy(), currentUser) && !isArticleAuthor(articleId, currentUser)) {
            throw new BusinessException("error.comment.onlyDeleteOwnThread");
        }
    }

    private boolean isArticleAuthor(Integer articleId, String currentUser) {
        ArticleDto article = articleService.getById(articleId);
        return article != null && isCreatedBy(article.getCreateBy(), currentUser);
    }

    private boolean isCreatedBy(String createBy, String currentUser) {
        return StrUtil.isNotBlank(createBy) && StrUtil.equals(createBy, currentUser);
    }

    private void fillCreateComment(ArticleCommentDto comment, LocalDateTime now, String currentUser) {
        comment.setCommentId(StrUtil.blankToDefault(comment.getCommentId(), createCommentId()));
        comment.setIsDelete(false);
        comment.setCreateBy(currentUser);
        comment.setUpdateBy(currentUser);
        comment.setCreateTime(now);
        comment.setUpdateTime(now);
    }

    private void touchThread(Integer articleId, String threadId, LocalDateTime now, String currentUser) {
        UpdateWrapper<ArticleCommentThreadDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ArticleCommentThreadPo.COL_ARTICLE_ID, articleId);
        updateWrapper.eq(ArticleCommentThreadPo.COL_THREAD_ID, threadId);
        updateWrapper.set(ArticleCommentThreadPo.COL_UPDATE_BY, currentUser);
        updateWrapper.set(ArticleCommentThreadPo.COL_UPDATE_TIME, now);
        update(updateWrapper);
    }

    private Boolean updateThread(Integer articleId, String threadId, LocalDateTime now, String currentUser, boolean resolved) {
        UpdateWrapper<ArticleCommentThreadDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(ArticleCommentThreadPo.COL_ARTICLE_ID, articleId);
        updateWrapper.eq(ArticleCommentThreadPo.COL_THREAD_ID, threadId);
        updateWrapper.set(ArticleCommentThreadPo.COL_RESOLVED_AT, resolved ? now : null);
        updateWrapper.set(ArticleCommentThreadPo.COL_UPDATE_BY, currentUser);
        updateWrapper.set(ArticleCommentThreadPo.COL_UPDATE_TIME, now);
        boolean updated = update(updateWrapper);
        if (!updated) {
            throw new BusinessException("error.comment.threadNotFound");
        }
        return true;
    }

    private String createThreadId() {
        return "thread-" + UUID.randomUUID();
    }

    private String createCommentId() {
        return "comment-" + UUID.randomUUID();
    }
}
