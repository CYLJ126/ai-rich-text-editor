package com.arte.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.richtext.ArticleCommentDto;
import com.arte.core.annotations.MybatisParams;

/**
 * 文章批注评论 Mapper
 *
 * @author Codex
 * @since 2026/6/20
 */
@MybatisParams(value = "arte_rt_article_comment", ignore = true)
public interface ArticleCommentMapper extends BaseMapper<ArticleCommentDto> {
}
