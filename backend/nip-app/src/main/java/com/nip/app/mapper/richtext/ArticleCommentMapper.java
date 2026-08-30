package com.nip.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.richtext.ArticleCommentDto;
import com.nip.core.annotations.MybatisParams;

/**
 * 文章批注评论 Mapper
 *
 * @author Codex
 * @since 2026/6/20
 */
@MybatisParams(value = "nip_rt_article_comment", ignore = true)
public interface ArticleCommentMapper extends BaseMapper<ArticleCommentDto> {
}
