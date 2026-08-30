package com.nip.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.richtext.ArticleCommentThreadDto;
import com.nip.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文章批注线程 Mapper
 *
 * @author Codex
 * @since 2026/6/20
 */
@MybatisParams(value = "nip_rt_article_comment_thread", ignore = true)
public interface ArticleCommentThreadMapper extends BaseMapper<ArticleCommentThreadDto> {

    List<ArticleCommentThreadDto> listThreadsWithComments(@Param("articleId") Integer articleId);
}
