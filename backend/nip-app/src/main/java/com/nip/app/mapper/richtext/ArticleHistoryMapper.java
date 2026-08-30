package com.nip.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.richtext.ArticleHistoryPo;
import com.nip.core.annotations.MybatisParams;

@MybatisParams(value = "nip_rt_article_history", ignore = true)
public interface ArticleHistoryMapper extends BaseMapper<ArticleHistoryPo> {
}
