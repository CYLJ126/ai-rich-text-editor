package com.nip.app.strategy.richtext.handler;

import com.nip.app.common.utils.TextExtractUtil;
import com.nip.app.common.utils.TokenCountUtil;
import com.nip.app.pojo.richtext.ChunkingContext;
import com.nip.app.pojo.richtext.TiptapNode;
import com.nip.app.service.richtext.TiptapJsonParser;
import com.nip.app.strategy.richtext.NodeHandlerStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 列表节点（orderedList / bulletList / taskList）处理策略。
 *
 * <p>分块规则：</p>
 * <ol>
 *     <li>列表作为独立语义单元，不与列表前后的其他节点合并。</li>
 *     <li>多个列表项累计不超过 MAX_TOKENS 时，合并到同一个 chunk。</li>
 *     <li>加入新列表项会超限时，普通 flush，不在列表项之间产生 overlap。</li>
 *     <li>单个列表项自身超长时，只在该列表项内部使用 overlap。</li>
 * </ol>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:10 ✾
 */
@Service
@Slf4j
public class ListNodeHandler implements NodeHandlerStrategy {

    @Override
    public boolean supports(String nodeType) {
        return "orderedList".equals(nodeType) || "bulletList".equals(nodeType) || "taskList".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        if (!node.hasContent()) {
            return;
        }
        List<TiptapNode> items = node.getContent();
        if (items == null || items.isEmpty()) {
            return;
        }
        /*
         * 列表是独立语义单元。
         * 先清理列表之前的段落、代码块等残留内容。
         */
        parser.flushBuffer(context);

        boolean orderedList = "orderedList".equals(node.getType());
        boolean taskList = "taskList".equals(node.getType());

        for (int index = 0; index < items.size(); index++) {
            TiptapNode item = items.get(index);
            if (item == null || !item.hasContent()) {
                continue;
            }
            handleItem(item, index, orderedList, taskList, node, context, parser);
        }
        /*
         * 列表结束后清空缓冲区，避免与列表之后的节点混合。
         */
        parser.flushBuffer(context);
    }

    /**
     * 处理单个列表项。
     */
    private void handleItem(TiptapNode item,
                            int index,
                            boolean orderedList,
                            boolean taskList,
                            TiptapNode listNode,
                            ChunkingContext context,
                            TiptapJsonParser parser) {
        Set<String> boldTerms = new LinkedHashSet<>();
        Set<String> highlightTerms = new LinkedHashSet<>();
        List<String> mediaRefs = new ArrayList<>();
        boolean[] hasStrikethrough = {false};

        String itemText = TextExtractUtil.extractTextWithMarks(item, boldTerms, highlightTerms, mediaRefs, hasStrikethrough);
        if (itemText == null || itemText.isBlank()) {
            return;
        }

        String prefix = buildPrefix(orderedList, taskList, index + 1, item);
        String fullText = prefix + itemText;
        int itemTokens = TokenCountUtil.estimate(fullText);

        String listNodeId = listNode.getAttrString("id");
        String itemNodeId = item.getAttrString("id");
        // 优先关联具体列表项 ID，取不到时再使用列表节点 ID。
        String nodeId = itemNodeId != null ? itemNodeId : listNodeId;
        log.debug("列表 ID={}，列表项 ID={}，第 {} 项 token 预估={}", listNodeId, itemNodeId, index + 1, itemTokens);
        /*
         * 普通列表项：
         * 多个 item 累计不超过 MAX_TOKENS 时保存在同一个 chunk 中。
         */
        if (itemTokens <= TokenCountUtil.MAX_TOKENS) {
            appendNormalItem(fullText, nodeId, boldTerms, highlightTerms, hasStrikethrough[0], mediaRefs, context, parser);
            return;
        }
        /*
         * 单个 item 自身超过限制：
         * 先结束前面的普通列表项，然后只在当前 item 内部进行 overlap 切分。
         */
        log.warn("列表 ID={}，第 {} 项 token={}，超过 MAX_TOKENS={}，进行 item 内部切分", listNodeId, index + 1, itemTokens, TokenCountUtil.MAX_TOKENS);
        handleOversizedItem(fullText, nodeId, boldTerms, highlightTerms, hasStrikethrough[0], mediaRefs, context, parser);
    }

    /**
     * 添加普通列表项。
     *
     * <p>如果加入当前 item 后会超限，先普通 flush。这里不使用 overlap，
     * 因为列表项之间是相对独立的语义单元。</p>
     */
    private void appendNormalItem(String text,
                                  String nodeId,
                                  Set<String> boldTerms,
                                  Set<String> highlightTerms,
                                  boolean hasStrikethrough,
                                  List<String> mediaRefs,
                                  ChunkingContext context,
                                  TiptapJsonParser parser) {
        ChunkingContext.BufferItem bufferItem = createBufferItem(text, nodeId, boldTerms, highlightTerms, hasStrikethrough, mediaRefs);
        int projectedTokenCount = context.getBufferTokenCount() + bufferItem.getCachedTokenCount();
        if (!context.isBufferEmpty() && projectedTokenCount > TokenCountUtil.MAX_TOKENS) {
            /*
             * 这里是列表项之间的边界，因此普通 flush，
             * 不调用 flushBufferWithOverlap()。
             */
            parser.flushBuffer(context);
        }
        context.addToBuffer(bufferItem);
    }

    /**
     * 处理单个超长列表项。
     *
     * <p>切分长度使用 MAX_TOKENS - OVERLAP_TOKENS，确保下一个分段加入
     * overlap 内容后仍然尽量不超过 MAX_TOKENS。</p>
     */
    private void handleOversizedItem(String fullText,
                                     String nodeId,
                                     Set<String> boldTerms,
                                     Set<String> highlightTerms,
                                     boolean hasStrikethrough,
                                     List<String> mediaRefs,
                                     ChunkingContext context,
                                     TiptapJsonParser parser) {
        /*
         * 超长 item 不与前面的普通 item 混合。
         */
        parser.flushBuffer(context);
        int segmentTokenLimit = Math.max(1, TokenCountUtil.MAX_TOKENS - TokenCountUtil.OVERLAP_TOKENS);

        List<String> segments = TokenCountUtil.splitByTokens(fullText, segmentTokenLimit);

        for (int index = 0; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (segment == null || segment.isBlank()) {
                continue;
            }

            ChunkingContext.BufferItem segmentItem = createBufferItem(
                    segment,
                    nodeId,
                    boldTerms,
                    highlightTerms,
                    hasStrikethrough,
                    /*
                     * 媒体引用只放在第一个分段中，避免多个 chunk 重复携带。
                     */
                    index == 0 ? mediaRefs : List.of()
            );
            int projectedTokenCount = context.getBufferTokenCount() + segmentItem.getCachedTokenCount();
            /*
             * 当前 buffer 中只有同一个超长 item 的前序内容，
             * 因此这里可以使用 overlap flush。
             */
            if (!context.isBufferEmpty() && projectedTokenCount > TokenCountUtil.MAX_TOKENS) {
                parser.flushBufferWithOverlap(context);
            }
            /*
             * 防御性判断：
             * TokenCountUtil.splitByTokens 理论上应确保 segment 不超限。
             */
            int projectedAfterOverlap = context.getBufferTokenCount() + segmentItem.getCachedTokenCount();

            if (!context.isBufferEmpty() && projectedAfterOverlap > TokenCountUtil.MAX_TOKENS) {
                log.warn("超长列表项 overlap 后仍会超限，执行普通 flush，nodeId={}，bufferTokens={}，segmentTokens={}",
                        nodeId, context.getBufferTokenCount(), segmentItem.getCachedTokenCount());
                parser.flushBuffer(context);
            }
            context.addToBuffer(segmentItem);
        }
        /*
         * 结束当前超长 item，避免其 overlap 残留与下一个列表项混合。
         */
        parser.flushBuffer(context);
    }

    private ChunkingContext.BufferItem createBufferItem(
            String text,
            String nodeId,
            Set<String> boldTerms,
            Set<String> highlightTerms,
            boolean hasStrikethrough,
            List<String> mediaRefs) {
        return new ChunkingContext.BufferItem(
                text,
                nodeId,
                new LinkedHashSet<>(boldTerms),
                new LinkedHashSet<>(highlightTerms),
                hasStrikethrough,
                new ArrayList<>(mediaRefs)
        );
    }

    private String buildPrefix(boolean orderedList, boolean taskList, int index, TiptapNode item) {
        if (taskList) {
            boolean checked = Boolean.parseBoolean(item.getAttrString("checked"));
            return checked ? "- [x] " : "- [ ] ";
        }
        if (orderedList) {
            return index + ". ";
        }
        return "- ";
    }
}