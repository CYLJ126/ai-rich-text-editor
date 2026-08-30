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

import java.util.List;
import java.util.UUID;

/**
 * Mermaid 图表节点处理策略
 * <p>
 * Tiptap 中 mermaid 节点的图表代码存储在 content 子节点中：
 * - "text" 节点：代码行文本
 * - "hardBreak" 节点：换行符
 * attrs.code 在前端渲染时使用，后端 JSON 中通常不携带
 * </p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:08 ✾
 **/
@Service
@Slf4j
public class MermaidNodeHandler implements NodeHandlerStrategy {
    @Override
    public boolean supports(String nodeType) {
        return "mermaid".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        // 先 flush 当前缓冲，mermaid 独立成块
        parser.flushBuffer(context);
        // 优先从 content 子节点提取代码（Tiptap 标准存储方式）
        String code = extractCodeFromContent(node);
        // 降级：兼容 attrs.code 存在的情况（某些旧版本或自定义扩展）
        if (code.isBlank()) {
            String attrCode = node.getAttrString("code");
            if (attrCode != null) code = attrCode.strip();
        }
        if (code.isBlank()) {
            log.debug("Mermaid node id={} has no code content, skipped",
                    node.getAttrString("id"));
            return;
        }
        String content = "[Mermaid图表]\n" + code;
        String breadcrumb = context.buildBreadcrumb();
        String chunkId = UUID.randomUUID().toString();
        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(node.getAttrString("id"))
                .chunkType(TiptapNodeTypeEnum.MERMAID)
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
        log.debug("Mermaid chunk created, nodeId={}, codeLines={}",
                node.getAttrString("id"), code.lines().count());
    }

    /**
     * 从 content 子节点序列中还原 mermaid 代码文本。
     * <p>
     * content 结构：[text, hardBreak, text, hardBreak, ...]
     * - type="text"      → 取 node.getText() 作为代码行
     * - type="hardBreak" → 输出换行符 \n
     * - 其他类型          → 忽略
     * </p>
     *
     * @return 还原后的代码字符串，无内容则返回空串
     */
    private String extractCodeFromContent(TiptapNode node) {
        if (!node.hasContent()) return "";
        List<TiptapNode> children = node.getContent();
        StringBuilder sb = new StringBuilder();
        for (TiptapNode child : children) {
            if (child == null || child.getType() == null) continue;
            switch (child.getType()) {
                case "text" -> {
                    String text = child.getText();
                    if (text != null) sb.append(text);
                }
                case "hardBreak" -> sb.append('\n');
                default -> log.trace("Unexpected child type '{}' in mermaid node, ignored",
                        child.getType());
            }
        }
        return sb.toString().strip();
    }
}
