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
 * 段落节点处理策略
 * <p>
 * 设计原则：段落是独立的语义单元，处理完一个段落后立即 flush，不与相邻段落合并 buffer，避免跨段内容污染。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:03 ✾
 */
@Service
@Slf4j
public class ParagraphNodeHandler implements NodeHandlerStrategy {

    /**
     * 单次 handle 最多允许 flush 的次数，防止极端情况死循环
     */
    private static final int MAX_FLUSH_PER_HANDLE = 1024;

    @Override
    public boolean supports(String nodeType) {
        return "paragraph".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        if (!node.hasContent()) return;

        Set<String> boldTerms = new LinkedHashSet<>();
        Set<String> highlightTerms = new LinkedHashSet<>();
        List<String> mediaRefs = new ArrayList<>();
        boolean[] hasStrikethrough = {false};

        String text = TextExtractUtil.extractTextWithMarks(
                node, boldTerms, highlightTerms, mediaRefs, hasStrikethrough
        );
        if (text.isBlank()) return;

        String nodeId = node.getAttrString("id");
        log.debug("节点 ID：{}，段落 token 预估：{}", nodeId, TokenCountUtil.estimate(text));

        // 每个段落独立处理：先 flush 上一段落残留的 buffer
        // 段落是天然语义边界，不允许跨段落合并 buffer
        if (!context.isBufferEmpty()) {
            parser.flushBuffer(context);
        }

        // 防御性切分：将超大段落切为 ≤ MAX_TOKENS 的子段
        List<String> segments = TokenCountUtil.splitByTokens(text, TokenCountUtil.MAX_TOKENS);

        int flushCount = 0;
        for (String segment : segments) {
            if (segment.isBlank()) continue;

            int segTokens = TokenCountUtil.estimate(segment);

            // 单个 segment 本身就超限（理论上 splitByTokens 已保证不超，但作为防御：直接 flush 当前 buffer 再单独成块，不入 buffer）
            if (segTokens > TokenCountUtil.MAX_TOKENS) {
                log.warn("节点 ID={} 存在单 segment 超 MAX_TOKENS（{}），强制独立成块", nodeId, segTokens);
                if (!context.isBufferEmpty()) {
                    parser.flushBuffer(context);
                    flushCount++;
                }
                // 直接构造单元素 buffer 并 flush
                ChunkingContext.BufferItem oversized = new ChunkingContext.BufferItem(
                        segment, nodeId,
                        new LinkedHashSet<>(boldTerms),
                        new LinkedHashSet<>(highlightTerms),
                        hasStrikethrough[0],
                        new ArrayList<>(mediaRefs)
                );
                context.addToBuffer(oversized);
                parser.flushBuffer(context);
                flushCount++;
                continue;
            }

            ChunkingContext.BufferItem item = new ChunkingContext.BufferItem(
                    segment, nodeId,
                    new LinkedHashSet<>(boldTerms),
                    new LinkedHashSet<>(highlightTerms),
                    hasStrikethrough[0],
                    new ArrayList<>(mediaRefs)
            );
            context.addToBuffer(item);

            // 段落内超限时使用 overlap flush
            int safeGuard = 0;
            while (context.getBufferTokenCount() > TokenCountUtil.MAX_TOKENS
                    && safeGuard < MAX_FLUSH_PER_HANDLE) {
                int tokensBefore = context.getBufferTokenCount();
                parser.flushBufferWithOverlap(context);
                flushCount++;
                safeGuard++;
                // 收敛检查：若 flush 后 token 数未减少，说明陷入死循环，强制清空
                if (context.getBufferTokenCount() >= tokensBefore) {
                    log.error("节点 ID={} flushBufferWithOverlap 未收敛（before={}, after={}），强制 flushBuffer",
                            nodeId, tokensBefore, context.getBufferTokenCount());
                    parser.flushBuffer(context);
                    flushCount++;
                    break;
                }
            }

            if (safeGuard >= MAX_FLUSH_PER_HANDLE) {
                log.error("节点 ID={} flush 次数达上限 {}，强制清空 buffer 防止 OOM",
                        nodeId, MAX_FLUSH_PER_HANDLE);
                parser.flushBuffer(context);
            }
        }

        // 段落处理完毕，flush 本段剩余内容（不等到下一个边界节点），确保本段内容不会与下一段落混合
        if (!context.isBufferEmpty()) {
            parser.flushBuffer(context);
        }

        if (flushCount > 2) {
            log.warn("节点 ID={} 触发 {} 次 flush，段落过长，建议业务侧拆分", nodeId, flushCount);
        }
    }
}