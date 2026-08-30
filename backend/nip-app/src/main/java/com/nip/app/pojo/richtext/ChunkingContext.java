package com.nip.app.pojo.richtext;

import cn.hutool.core.text.CharSequenceUtil;
import com.nip.app.common.utils.TokenCountUtil;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * 分块过程中的共享上下文，贯穿整个解析流程
 * 非线程安全，每次解析创建新实例
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:00 ✾
 **/
@Getter
public class ChunkingContext {
    private final Integer articleId;

    private final ArticleDocument articleMeta;
    /**
     * 面包屑栈，key=headingLevel, value=headingText
     */
    private final TreeMap<Integer, String> breadcrumbStack = new TreeMap<>();
    /**
     * 当前章节 heading 文本
     */
    @Setter
    private String currentSectionHeading;
    /**
     * 当前章节 heading 节点ID
     */
    @Setter
    private String currentSectionHeadingId;
    /**
     * 段落缓冲区，待合并成chunk
     */
    private final List<BufferItem> chunkBuffer = new ArrayList<>();
    /**
     * 已生成的所有 chunk
     */
    private final List<ChunkDocument> resultChunks = new ArrayList<>();
    /**
     * 全局 chunk 索引计数
     */
    private int chunkIndex = 0;
    /**
     * 当前 buffer 的累积 token 数（随 add/clear 实时维护）
     */
    private int bufferTokenCount = 0;

    public ChunkingContext(Integer articleId, ArticleDocument articleMeta) {
        this.articleId = articleId;
        this.articleMeta = articleMeta;
    }

    public int nextChunkIndex() {
        return chunkIndex++;
    }

    public boolean isBufferEmpty() {
        return chunkBuffer.isEmpty();
    }

    public void addToBuffer(BufferItem item) {
        chunkBuffer.add(item);
        bufferTokenCount += item.getCachedTokenCount();
    }

    public void clearBuffer() {
        chunkBuffer.clear();
        bufferTokenCount = 0;
    }

    /**
     * flush 后重置为 overlap 部分的 token 数
     */
    public void resetBufferTokenCount(int overlapTokenCount) {
        this.bufferTokenCount = overlapTokenCount;
    }

    /**
     * 构建当前面包屑字符串
     * 例: 文章标题 > H2标题 > H3标题
     */
    public String buildBreadcrumb() {
        StringBuilder sb = new StringBuilder();
        if (CharSequenceUtil.isNotBlank(articleMeta.getTitle())) {
            sb.append(articleMeta.getTitle());
        }
        breadcrumbStack.forEach((level, text) -> {
            if (!sb.isEmpty()) sb.append(" > ");
            sb.append(text);
        });
        return sb.toString();
    }

    /**
     * 更新面包屑栈：裁掉同级及以下的heading，追加新heading
     */
    public void updateBreadcrumb(int level, String headingText) {
        // 移除所有 level >= 当前level 的节点
        breadcrumbStack.tailMap(level, true).clear();
        breadcrumbStack.put(level, headingText);
    }

    /**
     * 缓冲区 item
     * 缓冲区中每一段文本的元数据
     */
    @Getter
    public static class BufferItem {
        private final String text;
        private final String nodeId;
        private final Set<String> boldTerms;
        private final Set<String> highlightTerms;
        private final boolean hasStrikethrough;
        private final List<String> mediaRefs;
        /**
         * 构造时计算并缓存，避免在 while 循环中反复调用 estimate()
         */
        private final int cachedTokenCount;

        public BufferItem(String text, String nodeId,
                          Set<String> boldTerms, Set<String> highlightTerms,
                          boolean hasStrikethrough, List<String> mediaRefs) {
            this.text = text != null ? text : "";
            this.nodeId = nodeId;
            this.boldTerms = boldTerms != null ? boldTerms : new LinkedHashSet<>();
            this.highlightTerms = highlightTerms != null ? highlightTerms : new LinkedHashSet<>();
            this.hasStrikethrough = hasStrikethrough;
            // BufferItem 会在后续节点处理过程中追加媒体引用，因此不能直接
            // 持有调用方传入的 List.of()/Stream.toList() 等不可变列表。
            this.mediaRefs = mediaRefs != null ? new ArrayList<>(mediaRefs) : new ArrayList<>();
            // 仅计算一次
            this.cachedTokenCount = TokenCountUtil.estimate(this.text);
        }

        /**
         * 按字符偏移量切分当前 item，产生两个子 item（共享 marks 信息）
         * 用于单个 item 文本超过 MAX_TOKENS 时的强制拆分
         *
         * @param charOffset 切分的字符位置（不是 token 位置）
         */
        public BufferItem[] splitAt(int charOffset) {
            String head = text.substring(0, charOffset);
            String tail = text.substring(charOffset);
            // head 共享所有 marks（保守策略：两段都带相同 marks）
            BufferItem headItem = new BufferItem(head, nodeId,
                    new LinkedHashSet<>(boldTerms), new LinkedHashSet<>(highlightTerms),
                    hasStrikethrough, new ArrayList<>(mediaRefs));
            BufferItem tailItem = new BufferItem(tail, nodeId,
                    new LinkedHashSet<>(boldTerms), new LinkedHashSet<>(highlightTerms),
                    hasStrikethrough, new ArrayList<>());
            return new BufferItem[]{headItem, tailItem};
        }

        /**
         * 追加媒体引用到末尾（不影响 token 计数，媒体引用不参与切分判断）
         */
        public void appendMediaRef(String ref) {
            mediaRefs.add(ref);
        }
    }
}
