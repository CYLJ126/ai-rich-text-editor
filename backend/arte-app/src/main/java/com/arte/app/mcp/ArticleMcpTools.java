package com.arte.app.mcp;

import com.arte.app.api.richtext.ArticleService;
import com.arte.app.common.enums.richtext.ArticleAccessLevelEnum;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.core.es.EsSearchResponse;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * 文章相关 MCP 服务
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/16 20:51 ✾
 **/
@Slf4j
@Service
public class ArticleMcpTools {
    @Resource
    private ArticleService articleService;

    @McpTool(
            name = "list-articles",
            description = "Query the accessible article list by search conditions"
    )
    public ResultContext<EsSearchResponse<ChunkDocument>> searchArticlesChunks(
            @McpToolParam(
                    description = "Search keyword",
                    required = false
            ) String searchText,
            @McpToolParam(
                    description = "Page number, starting from 1. Default: 1",
                    required = false
            ) Long current,
            @McpToolParam(
                    description = "Number of results per page. Default: 10",
                    required = false
            ) Long size,
            @McpToolParam(
                    description = "Whether to search article titles instead of content",
                    required = false
            ) Boolean searchTitle,
            @McpToolParam(
                    description = "Whether to enable semantic vector search",
                    required = false
            ) Boolean semanticSearch,
            @McpToolParam(
                    description = "Access level: PUBLIC, PRIVATE, or TEAM",
                    required = false
            ) ArticleAccessLevelEnum accessLevel) {

        ArticleParam param = new ArticleParam();
        param.setSearchBingoText(searchText);
        param.setCurrent(current == null ? 1L : current);
        param.setSize(size == null ? 10L : size);
        param.setSearchTitle(Boolean.TRUE.equals(searchTitle));
        param.setSemanticSearch(Boolean.TRUE.equals(semanticSearch));
        param.setAccessLevel(accessLevel);
        log.info("MCP-文章列表查询参数：【{}】", param);
        return ResultContext.wrap(param, articleService::hybridSearch);
    }
}
