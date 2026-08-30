package com.arte.app.api.richtext;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.richtext.ArticleCommentDto;
import com.arte.app.pojo.richtext.ArticleCommentThreadDto;

import java.util.List;

/**
 * 文章批注线程服务
 *
 * @author Codex
 * @since 2026/6/20
 */
public interface ArticleCommentThreadService extends IService<ArticleCommentThreadDto> {

    List<ArticleCommentThreadDto> listThreads(Integer articleId);

    ArticleCommentThreadDto createThread(ArticleCommentThreadDto param);

    ArticleCommentDto addComment(ArticleCommentDto param);

    Boolean updateComment(ArticleCommentDto param);

    Boolean deleteComment(ArticleCommentDto param);

    Boolean resolveThread(Integer articleId, String threadId);

    Boolean unresolveThread(Integer articleId, String threadId);

    Boolean deleteThread(Integer articleId, String threadId);
}
