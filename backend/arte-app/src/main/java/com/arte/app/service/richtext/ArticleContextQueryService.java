package com.arte.app.service.richtext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 文章领域的只读投影；调用方负责资源授权，不依赖旧 ThreadLocal 或 AI 实体。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${arte.ai-new.action.enabled:false} or ${arte.ai-new.chat.enabled:false}")
public class ArticleContextQueryService {
    public record ArticleText(String id, String title, String version, String text) { }
    private final JdbcTemplate jdbc;
    public ArticleContextQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ArticleText> find(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("invalid article id");
        int number = Integer.parseInt(id);
        return jdbc.query("SELECT id,title,row_version,content_text FROM arte_rt_article WHERE id=? AND is_delete=0", (row, index) -> {
            var version = row.getObject("row_version", Integer.class);
            return new ArticleText(Integer.toString(row.getInt("id")), row.getString("title"), version == null ? null : version.toString(), row.getString("content_text"));
        }, number).stream().findFirst();
    }
}
