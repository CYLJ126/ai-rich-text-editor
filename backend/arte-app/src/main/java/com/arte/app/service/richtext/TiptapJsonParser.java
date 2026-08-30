package com.arte.app.service.richtext;

import com.arte.ai.api.EmbeddingService;
import com.arte.app.common.enums.richtext.TiptapNodeTypeEnum;
import com.arte.app.common.utils.TokenCountUtil;
import com.arte.app.pojo.richtext.ArticleDocument;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.ChunkingContext;
import com.arte.app.pojo.richtext.TiptapNode;
import com.arte.app.strategy.richtext.NodeHandlerStrategy;
import com.arte.core.serialize.SerializerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/**
 * Tiptap JSON 解析器与分块核心类
 * <p>
 * 职责：
 * 1. 将 Tiptap JSON 字符串解析为 {@link TiptapNode} 树
 * 2. 深度优先遍历节点树，委托各 {@link NodeHandlerStrategy} 处理
 * 3. 管理段落缓冲区的 flush 逻辑（含 overlap）
 * 4. 链接相邻 chunk（prev/next）
 * </p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:38 ✾
 **/
@Service
@Slf4j
public class TiptapJsonParser {
    /**
     * JSON 解析器，用于将 JSON 字符串转换为 {@link TiptapNode} 树
     */
    private final ObjectMapper objectMapper;
    /**
     * 按节点类型注册的处理策略，使用 Map 加速查找
     */
    private final Map<String, NodeHandlerStrategy> handlerMap;
    /**
     * 嵌入模型
     */
    private final EmbeddingService embeddingService;

    public TiptapJsonParser(List<NodeHandlerStrategy> strategies, EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.objectMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();
        // 将策略列表转换为 Map，按第一个匹配的 nodeType 注册
        this.handlerMap = new LinkedHashMap<>();
        for (NodeHandlerStrategy strategy : strategies) {
            // 这里约定：supports 方法仅匹配单个 nodeType
            // 多类型 handler（如 MathNodeHandler）自行在 supports 中判断
            for (String type : resolveTypes(strategy)) {
                handlerMap.put(type, strategy);
            }
        }
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------
    /**
     * 解析 Tiptap JSON 字符串，返回分块文档列表
     *
     * @param tiptapJson  Tiptap getJSON() 返回的 JSON 字符串
     * @param articleId   文章ID
     * @param articleDocument 文章元信息
     * @return 分块文档列表（embedding 字段未填充，需外部调用）
     */
    public List<ChunkDocument> parse(String tiptapJson, Integer articleId, ArticleDocument articleDocument) {
        Objects.requireNonNull(tiptapJson, "tiptapJson 不能为空");
        Objects.requireNonNull(articleId, "articleId 不能为空");
        TiptapNode doc;
        try {
            doc = objectMapper.readValue(tiptapJson, TiptapNode.class);
        } catch (Exception e) {
            log.error("解析 Tiptap JSON 异常，articleId={}", articleId, e);
            throw new IllegalArgumentException("无效的 Tiptap JSON: " + e.getMessage(), e);
        }
        if (!"doc".equals(doc.getType())) {
            throw new IllegalArgumentException("根节点类型必须为 'doc'，实际为: " + doc.getType());
        }
        ChunkingContext context = new ChunkingContext(articleId, articleDocument);
        traverseNodes(doc.getContent(), context);
        // 遍历结束，flush 剩余缓冲
        flushBuffer(context);
        // 填充时间戳 & 链接前后 chunk
        Instant now = Instant.now();
        // 过滤掉标题分块
        List<ChunkDocument> chunks = context.getResultChunks().stream()
                .filter(chunk -> !Objects.equals(chunk.getChunkType(), TiptapNodeTypeEnum.HEADING)).toList();
        int size = chunks.size();
        for (int i = 0; i < size; i++) {
            ChunkDocument chunk = chunks.get(i);
            chunk.setEmbedding(embeddingService.generateEmbedding(chunk.getContent()));
            // 链接相邻 Chunk
            chunk.setPrevChunkId(i > 0 ? chunks.get(i - 1).getChunkId() : null);
            chunk.setNextChunkId(i < size - 1 ? chunks.get(i + 1).getChunkId() : null);
            chunk.setCreateTime(now);
            chunk.setUpdateTime(now);
        }
        log.info("解析文章 articleId={} 成功，共 {} 个分块", articleId, context.getResultChunks().size());
        return context.getResultChunks();
    }

    // ----------------------------------------------------------------
    //  节点遍历
    // ----------------------------------------------------------------
    /**
     * 深度优先遍历节点列表
     */
    public void traverseNodes(List<TiptapNode> nodes, ChunkingContext context) {
        if (nodes == null || nodes.isEmpty()) return;
        for (TiptapNode node : nodes) {
            processNode(node, context);
        }
    }

    /**
     * 处理单个节点：优先查 handlerMap，其次处理容器型节点，否则跳过
     */
    private void processNode(TiptapNode node, ChunkingContext context) {
        if (node == null || node.getType() == null) return;
        String type = node.getType();
        NodeHandlerStrategy handler = handlerMap.get(type);
        if (handler != null) {
            handler.handle(node, context, this);
            return;
        }
        // 容器型节点：透传递归子节点
        if (TiptapNodeTypeEnum.CONTAINER_NODE_TYPES.contains(type)) {
            traverseNodes(node.getContent(), context);
            return;
        }
        // 未知节点：尝试提取文本并入缓冲（降级处理）
        if (node.hasContent()) {
            log.debug("未处理的容器节点类型 type='{}'，回退到文本提取处理", type);
            traverseNodes(node.getContent(), context);
        } else if ("text".equals(type) && node.getText() != null) {
            // 孤立 text 节点，直接忽略（正常不应出现在顶层）
        }
    }

    // ----------------------------------------------------------------
    //  Buffer Flush 逻辑（供 Handler 调用）
    // ----------------------------------------------------------------
    /**
     * 将缓冲区全部内容 flush 为一个 chunk（无重叠）
     * 用于遇到边界节点（heading / code_block / table 等）前的清空
     */
    public void flushBuffer(ChunkingContext context) {
        if (context.isBufferEmpty()) return;
        List<ChunkingContext.BufferItem> buffer = context.getChunkBuffer();
        buildChunkFromBuffer(buffer, 0, context, false);
        context.clearBuffer();
    }

    /**
     * 缓冲区超过 MAX_TOKENS 时调用，每次恰好 flush 一个 chunk。
     * overlap item 超过 OVERLAP_TOKENS 时，需截取 item 尾部文本，而非保留整个 item，否则 buffer 无法收缩导致死循环。
     */
    public void flushBufferWithOverlap(ChunkingContext context) {
        if (context.isBufferEmpty()) return;
        List<ChunkingContext.BufferItem> buffer = context.getChunkBuffer();
        // ── Step 1: 找切分点 ──────────────────────────────────────────
        int splitIndex = findSplitIndex(buffer, TokenCountUtil.MAX_TOKENS);
        if (splitIndex == 0) splitIndex = 1;
        // ── Step 2: 生成 chunk（subList 视图，不 new ArrayList）────────
        List<ChunkingContext.BufferItem> flushItems = buffer.subList(0, splitIndex);
        buildChunkFromBuffer(flushItems, TokenCountUtil.OVERLAP_TOKENS, context, true);
        // ── Step 3: 构造 overlap BufferItem ───────────────────────────
        //  overlap 语义：从 flushItems 末尾取 OVERLAP_TOKENS 个字符的文本片段
        //  不能直接保留整个 item（item token 数可能远超 OVERLAP_TOKENS）
        ChunkingContext.BufferItem overlapItem = buildOverlapItem(flushItems, TokenCountUtil.OVERLAP_TOKENS);
        // ── Step 4: 重建 buffer = [overlapItem] + buffer[splitIndex..] ─
        //  先删除已 flush 的部分
        buffer.subList(0, splitIndex).clear();
        //  将 overlapItem 插到头部（仅当 overlap 有实质内容时）
        if (overlapItem != null) {
            buffer.addFirst(overlapItem);
        }
        // ── Step 5: 重新统计 buffer token 数 ──────────────────────────
        int newTokenCount = 0;
        for (ChunkingContext.BufferItem item : buffer) {
            newTokenCount += item.getCachedTokenCount();
        }
        context.resetBufferTokenCount(newTokenCount);
    }

    /**
     * 从已 flush 的 items 末尾提取 overlapTokens 个 token 的文本，
     * 构造一个新的 BufferItem 作为下一个 chunk 的 overlap 前缀。
     *
     * <p>策略：
     * <ol>
     *   <li>从末尾 item 往前累计，直到 token 数 >= overlapTokens</li>
     *   <li>若最后一个 item 本身 token 数已超过 overlapTokens，
     *       则截取该 item 文本的 <b>尾部</b> overlapTokens 个 token 对应的字符片段</li>
     *   <li>多个 item 拼接时，取从 overlapStart 到末尾的完整文本</li>
     * </ol>
     *
     * @param flushItems    本次已 flush 的 items（subList 视图）
     * @param overlapTokens 目标 overlap token 数
     * @return overlap BufferItem，若无内容则返回 null
     */
    private ChunkingContext.BufferItem buildOverlapItem(
            List<ChunkingContext.BufferItem> flushItems, int overlapTokens) {

        if (flushItems.isEmpty()) return null;

        // 1. 从末尾往前累积，找到需要参与 overlap 的 item 起始下标
        int accumulated = 0;
        int overlapStart = flushItems.size(); // exclusive start（从末尾收缩）
        for (int i = flushItems.size() - 1; i >= 0; i--) {
            accumulated += flushItems.get(i).getCachedTokenCount();
            overlapStart = i;
            if (accumulated >= overlapTokens) break;
        }

        // 2. 拼接 overlap 范围内的文本
        StringBuilder overlapSb = new StringBuilder();
        for (int i = overlapStart; i < flushItems.size(); i++) {
            if (!overlapSb.isEmpty()) overlapSb.append("\n");
            overlapSb.append(flushItems.get(i).getText());
        }
        String overlapText = overlapSb.toString();

        // 3. 若仍超限，从尾部截取
        if (TokenCountUtil.estimate(overlapText) > overlapTokens) {
            overlapText = trimToLastNTokens(overlapText, overlapTokens);
        }
        if (overlapText.isBlank()) return null;

        // 4. 复用最后一个 item 的 marks，标记为 overlapOnly=true
        ChunkingContext.BufferItem lastItem = flushItems.getLast();
        return new ChunkingContext.BufferItem(
                overlapText,
                lastItem.getNodeId(),
                new LinkedHashSet<>(lastItem.getBoldTerms()),
                new LinkedHashSet<>(lastItem.getHighlightTerms()),
                lastItem.isHasStrikethrough(),
                new ArrayList<>()  // overlap 不重复携带 mediaRefs
        );
    }

    /**
     * 从字符串尾部截取约 targetTokens 个 token 对应的文本。
     * 使用双指针从末尾向前扫描，零额外大对象分配。
     *
     * @param text         原始文本
     * @param targetTokens 目标 token 数上限
     * @return 截取后的尾部文本
     */
    private String trimToLastNTokens(String text, int targetTokens) {
        if (text == null || text.isEmpty()) return "";
        // 从尾部向前逐字符累计 token 数，找到截取起点
        int accumulated = 0;
        int cutStart = text.length();
        int asciiRun = 0;

        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            int delta = 0;
            if (TokenCountUtil.isCjk(c)) {
                delta = 1;
            } else if (Character.isLetterOrDigit(c)) {
                asciiRun++;
                // asciiRun 在向前扫描时延迟计算
            } else {
                if (asciiRun > 0) {
                    delta += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
                if (!Character.isWhitespace(c)) {
                    delta = 1;
                }
            }
            accumulated += delta;
            cutStart = i;
            if (accumulated >= targetTokens) {
                break;
            }
        }
        // 处理末尾剩余的 asciiRun（向前扫描时未被计入）
        return text.substring(cutStart).stripLeading();
    }

    // ----------------------------------------------------------------
    //  Chunk 构建
    // ----------------------------------------------------------------

    /**
     * 修复：
     * 1. 消除 contentSb.toString().contains(ref) 的反复大对象分配
     * 2. 使用 Set 去重 mediaRefs，O(1) 判重
     */
    private void buildChunkFromBuffer(List<ChunkingContext.BufferItem> items,
                                      int overlapTokenCount,
                                      ChunkingContext context,
                                      boolean hasOverlap) {
        if (items.isEmpty()) return;
        // 合并文本
        StringBuilder contentSb = new StringBuilder();
        Set<String> boldTerms = new LinkedHashSet<>();
        Set<String> highlightTerms = new LinkedHashSet<>();
        // 用 Set 去重，避免 contentSb.toString().contains() 的反复全串扫描
        Set<String> mediaRefSet = new LinkedHashSet<>();
        List<String> nodeIds = new ArrayList<>();
        boolean hasStrikethrough = false;

        for (ChunkingContext.BufferItem item : items) {
            if (!contentSb.isEmpty()) contentSb.append("\n");
            // 追加主文本
            contentSb.append(item.getText());
            // O(1) 去重，不再 toString() 大 StringBuilder
            for (String ref : item.getMediaRefs()) {
                if (mediaRefSet.add(ref)) {
                    // 仅首次出现时追加到文本（如需在内容中体现媒体引用）
                    contentSb.append(" ").append(ref);
                }
            }

            boldTerms.addAll(item.getBoldTerms());
            highlightTerms.addAll(item.getHighlightTerms());
            hasStrikethrough = hasStrikethrough || item.isHasStrikethrough();
            if (item.getNodeId() != null) nodeIds.add(item.getNodeId());
        }

        String content = contentSb.toString().trim();
        if (content.isBlank()) return;

        String breadcrumb = context.buildBreadcrumb();
        String contentWithBreadcrumb = breadcrumb.isBlank()
                ? content
                : breadcrumb + "\n" + content;

        String chunkId = UUID.randomUUID().toString();
        String primaryNodeId = nodeIds.isEmpty() ? null : nodeIds.getFirst();
        List<String> mediaRefs = mediaRefSet.isEmpty() ? null : new ArrayList<>(mediaRefSet);

        ChunkDocument chunk = ChunkDocument.builder()
                .chunkId(chunkId)
                .articleId(context.getArticleId())
                .tiptapNodeId(primaryNodeId)
                .tiptapNodeIds(nodeIds.size() > 1 ? nodeIds : null)
                .chunkType(TiptapNodeTypeEnum.PARAGRAPH)
                .content(content)
                .contentWithBreadcrumb(contentWithBreadcrumb)
                .breadcrumb(breadcrumb)
                .boldTerms(boldTerms.isEmpty() ? null : String.join(" ", boldTerms))
                .highlightTerms(highlightTerms.isEmpty() ? null : String.join(" ", highlightTerms))
                .hasStrikethrough(hasStrikethrough)
                .sectionHeading(context.getCurrentSectionHeading())
                .sectionHeadingId(context.getCurrentSectionHeadingId())
                .chunkIndex(context.nextChunkIndex())
                .tokenCount(TokenCountUtil.estimate(content))
                .overlapTokens(hasOverlap ? overlapTokenCount : 0)
                .mediaRefs(mediaRefs)
                .articleMeta(context.getArticleMeta())
                .build();

        context.getResultChunks().add(chunk);
    }

    // ----------------------------------------------------------------
    //  分割点计算
    // ----------------------------------------------------------------
    /**
     * 找到缓冲区中累计 token 数达到 maxTokens 的 item 下标（exclusive）
     */
    private int findSplitIndex(List<ChunkingContext.BufferItem> buffer, int maxTokens) {
        int accumulated = 0;
        for (int i = 0; i < buffer.size(); i++) {
            accumulated += TokenCountUtil.estimate(buffer.get(i).getText());
            if (accumulated >= maxTokens) {
                return i + 1;
            }
        }
        return buffer.size();
    }

    // ----------------------------------------------------------------
    //  策略注册辅助
    // ----------------------------------------------------------------
    /**
     * 通过 supports 方法扫描已知节点类型，获取该 strategy 对应的类型列表
     * 约定的节点类型集合（可扩展）
     *
     * @param strategy 节点处理策略
     * @return 支持的节点类型列表
     */
    private List<String> resolveTypes(NodeHandlerStrategy strategy) {
        List<String> matched = new ArrayList<>();
        for (String type : TiptapNodeTypeEnum.KNOWN_NODE_TYPES) {
            if (strategy.supports(type)) {
                matched.add(type);
            }
        }
        return matched;
    }
}
