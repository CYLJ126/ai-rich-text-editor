package com.nip.app.strategy.richtext.handler;

import com.nip.app.common.enums.richtext.TiptapNodeTypeEnum;
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
 * 数学公式节点：
 * - blockMath  : 顶层节点，独立成 chunk
 * - inlineMath : 段落子节点，由 TextExtractUtil 在段落提取时处理，
 * 此 handler 仅作兜底（若 inlineMath 意外出现在顶层）
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:09 ✾
 **/
@Service
@Slf4j
public class MathNodeHandler implements NodeHandlerStrategy {
    @Override
    public boolean supports(String nodeType) {
        // ✅ 与实际 JSON 中的 type 字段保持一致
        return "blockMath".equals(nodeType) || "inlineMath".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        String type = node.getType();
        String latex = node.getAttrString("latex");
        if (latex == null || latex.isBlank()) {
            log.debug("Math node id={} has empty latex, skipped", node.getAttrString("id"));
            return;
        }
        if ("blockMath".equals(type)) {
            handleBlockMath(node, latex, context, parser);
        } else {
            // inlineMath 出现在顶层（非段落子节点）时的兜底处理：并入缓冲区。（实际上不会出现，内联代码已在段落提取时处理）
            handleTopLevelInlineMath(node, latex, context, parser);
        }
    }

    // ----------------------------------------------------------------
    //  Block math：独立 chunk
    // ----------------------------------------------------------------
    private void handleBlockMath(TiptapNode node, String latex,
                                 ChunkingContext context, TiptapJsonParser parser) {
        parser.flushBuffer(context);
        String content = "[数学公式]\n" + latex;
        String breadcrumb = context.buildBreadcrumb();
        String chunkId = UUID.randomUUID().toString();
        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(node.getAttrString("id"))
                .chunkType(TiptapNodeTypeEnum.BLOCK_MATH)
                .content(content)
                .contentWithBreadcrumb(
                        breadcrumb.isBlank() ? content : breadcrumb + "\n" + content)
                .breadcrumb(breadcrumb)
                .sectionHeading(context.getCurrentSectionHeading())
                .sectionHeadingId(context.getCurrentSectionHeadingId())
                .chunkIndex(context.nextChunkIndex())
                .tokenCount(TokenCountUtil.estimate(content))
                .overlapTokens(0)
                .articleMeta(context.getArticleMeta())
                .hasStrikethrough(false)
                .build();
        context.getResultChunks().add(chunk);
        log.debug("BlockMath chunk created, nodeId={}", node.getAttrString("id"));
    }

    // ----------------------------------------------------------------
    //  Inline math 出现在顶层时的兜底：以 $latex$ 形式并入段落缓冲
    // ----------------------------------------------------------------
    private void handleTopLevelInlineMath(TiptapNode node, String latex,
                                          ChunkingContext context, TiptapJsonParser parser) {
        String inlineText = "$" + latex + "$";
        log.debug("Top-level inlineMath encountered, merging into buffer: {}", inlineText);
        ChunkingContext.BufferItem item = new ChunkingContext.BufferItem(
                inlineText,
                node.getAttrString("id"),
                new java.util.LinkedHashSet<>(),
                new java.util.LinkedHashSet<>(),
                false,
                new java.util.ArrayList<>()
        );
        context.addToBuffer(item);
        while (context.getBufferTokenCount() > TokenCountUtil.MAX_TOKENS) {
            parser.flushBufferWithOverlap(context);
        }
    }
}