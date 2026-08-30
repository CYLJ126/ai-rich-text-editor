package com.nip.app.strategy.richtext.handler;

import com.nip.app.common.enums.richtext.TiptapNodeTypeEnum;
import com.nip.app.common.utils.TextExtractUtil;
import com.nip.app.common.utils.TokenCountUtil;
import com.nip.app.pojo.richtext.ChunkDocument;
import com.nip.app.pojo.richtext.ChunkingContext;
import com.nip.app.pojo.richtext.TiptapNode;
import com.nip.app.service.richtext.TiptapJsonParser;
import com.nip.app.strategy.richtext.NodeHandlerStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 标题节点处理策略
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:02 ✾
 **/
@Service
@Slf4j
public class HeadingNodeHandler implements NodeHandlerStrategy {

    @Override
    public boolean supports(String nodeType) {
        return "heading".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        // 1. 先 flush 之前的段落缓冲
        parser.flushBuffer(context);
        // 2. 提取标题纯文本
        String headingText = TextExtractUtil.extractPlainText(node);
        int level = node.getAttrInt("level") != null ? node.getAttrInt("level") : 1;
        // 3. 更新面包屑和当前章节
        context.updateBreadcrumb(level, headingText);
        context.setCurrentSectionHeading(headingText);
        context.setCurrentSectionHeadingId(node.getAttrString("id"));
        String breadcrumb = context.buildBreadcrumb();
        String chunkId = UUID.randomUUID().toString();
        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(node.getAttrString("id"))
                .chunkType(TiptapNodeTypeEnum.HEADING)
                .content(headingText)
                .contentWithBreadcrumb(breadcrumb)
                .breadcrumb(breadcrumb)
                .sectionHeading(headingText)
                .sectionHeadingId(node.getAttrString("id"))
                .headingLevel(level)
                .chunkIndex(context.nextChunkIndex())
                .tokenCount(TokenCountUtil.estimate(headingText))
                .overlapTokens(0)
                .articleMeta(context.getArticleMeta())
                .hasStrikethrough(false)
                .build();
        context.getResultChunks().add(chunk);
    }
}
