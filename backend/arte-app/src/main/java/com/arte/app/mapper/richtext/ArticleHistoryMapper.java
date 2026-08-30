package com.arte.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.richtext.ArticleHistoryPo;
import com.arte.core.annotations.MybatisParams;

@MybatisParams(value = "arte_rt_article_history", ignore = true)
public interface ArticleHistoryMapper extends BaseMapper<ArticleHistoryPo> {
}
