package com.nip.app.strategy.richtext.handler;

import com.nip.app.common.enums.richtext.TiptapNodeTypeEnum;
import com.nip.app.common.utils.TableSerializeUtil;
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
 * 表格节点处理策略
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:05 ✾
 **/
@Service
@Slf4j
public class TableNodeHandler implements NodeHandlerStrategy {
    @Override
    public boolean supports(String nodeType) {
        return "table".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        parser.flushBuffer(context);
        String mdTable = TableSerializeUtil.toMarkdown(node);
        if (mdTable.isBlank()) return;
        String breadcrumb = context.buildBreadcrumb();
        String chunkId = UUID.randomUUID().toString();
        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(node.getAttrString("id"))
                .chunkType(TiptapNodeTypeEnum.TABLE)
                .content(mdTable)
                .contentWithBreadcrumb(
                        breadcrumb.isBlank() ? mdTable : breadcrumb + "\n" + mdTable
                )
                .breadcrumb(breadcrumb)
                .sectionHeading(context.getCurrentSectionHeading())
                .sectionHeadingId(context.getCurrentSectionHeadingId())
                .chunkIndex(context.nextChunkIndex())
                .tokenCount(TokenCountUtil.estimate(mdTable))
                .overlapTokens(0)
                .articleMeta(context.getArticleMeta())
                .hasStrikethrough(false)
                .build();
        context.getResultChunks().add(chunk);
    }
}