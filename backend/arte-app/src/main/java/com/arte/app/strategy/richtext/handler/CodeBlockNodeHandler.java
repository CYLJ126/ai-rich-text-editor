package com.arte.app.strategy.richtext.handler;

import com.arte.app.common.enums.richtext.TiptapNodeTypeEnum;
import com.arte.app.common.utils.TextExtractUtil;
import com.arte.app.common.utils.TokenCountUtil;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.ChunkingContext;
import com.arte.app.pojo.richtext.TiptapNode;
import com.arte.app.service.richtext.TiptapJsonParser;
import com.arte.app.strategy.richtext.NodeHandlerStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 代码块节点处理策略
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:03 ✾
 **/
@Service
@Slf4j
public class CodeBlockNodeHandler implements NodeHandlerStrategy {
    @Override
    public boolean supports(String nodeType) {
        return "codeBlock".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        // 先 flush 段落缓冲
        parser.flushBuffer(context);
        String code = TextExtractUtil.extractPlainText(node);
        if (code.isBlank()) return;
        String language = node.getAttrString("language");
        String breadcrumb = context.buildBreadcrumb();
        String chunkId = UUID.randomUUID().toString();
        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(node.getAttrString("id"))
                .chunkType(TiptapNodeTypeEnum.CODE_BLOCK)
                .content(code)
                .contentWithBreadcrumb(
                        breadcrumb.isBlank() ? code : breadcrumb + "\n" + code
                )
                .breadcrumb(breadcrumb)
                .sectionHeading(context.getCurrentSectionHeading())
                .sectionHeadingId(context.getCurrentSectionHeadingId())
                .codeLanguage(language)
                .chunkIndex(context.nextChunkIndex())
                .tokenCount(TokenCountUtil.estimate(code))
                .overlapTokens(0)
                .articleMeta(context.getArticleMeta())
                .hasStrikethrough(false)
                .build();
        context.getResultChunks().add(chunk);
    }
}
