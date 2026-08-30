package com.arte.app.web.controller.richtext;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import com.arte.app.api.richtext.ArticleCommentThreadService;
import com.arte.app.pojo.richtext.ArticleCommentDto;
import com.arte.app.pojo.richtext.ArticleCommentThreadDto;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文章批注控制器
 *
 * @author Codex
 * @since 2026/6/20
 */
@RestController
@RequestMapping("/richText/comment")
public class ArticleCommentController {

    @Resource
    private ArticleCommentThreadService articleCommentThreadService;

    @AnonymousAccess
    @PostMapping("/listThreads")
    public ResultContext<List<ArticleCommentThreadDto>> listThreads(@RequestBody ArticleCommentThreadDto param) {
        Assert.notNull(param.getArticleId(), MessageUtils.get("error.field.articleIdRequired"));
        return ResultContext.success(articleCommentThreadService.listThreads(param.getArticleId()));
    }

    @AnonymousAccess
    @PostMapping("/createThread")
    public ResultContext<ArticleCommentThreadDto> createThread(@RequestBody ArticleCommentThreadDto param) {
        return ResultContext.success(articleCommentThreadService.createThread(param));
    }

    @AnonymousAccess
    @PostMapping("/addComment")
    public ResultContext<ArticleCommentDto> addComment(@RequestBody ArticleCommentDto param) {
        return ResultContext.success(articleCommentThreadService.addComment(param));
    }

    @AnonymousAccess
    @PostMapping("/updateComment")
    public ResultContext<Boolean> updateComment(@RequestBody ArticleCommentDto param) {
        return ResultContext.success(articleCommentThreadService.updateComment(param));
    }

    @AnonymousAccess
    @PostMapping("/deleteComment")
    public ResultContext<Boolean> deleteComment(@RequestBody ArticleCommentDto param) {
        return ResultContext.success(articleCommentThreadService.deleteComment(param));
    }

    @AnonymousAccess
    @PostMapping("/resolveThread")
    public ResultContext<Boolean> resolveThread(@RequestBody ArticleCommentThreadDto param) {
        return ResultContext.success(articleCommentThreadService.resolveThread(param.getArticleId(), param.getThreadId()));
    }

    @AnonymousAccess
    @PostMapping("/unresolveThread")
    public ResultContext<Boolean> unresolveThread(@RequestBody ArticleCommentThreadDto param) {
        return ResultContext.success(articleCommentThreadService.unresolveThread(param.getArticleId(), param.getThreadId()));
    }

    @AnonymousAccess
    @PostMapping("/deleteThread")
    public ResultContext<Boolean> deleteThread(@RequestBody ArticleCommentThreadDto param) {
        return ResultContext.success(articleCommentThreadService.deleteThread(param.getArticleId(), param.getThreadId()));
    }
}
