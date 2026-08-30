package com.arte.app.strategy.richtext.handler;

import com.arte.app.common.utils.MediaRefUtil;
import com.arte.app.pojo.richtext.ChunkingContext;
import com.arte.app.pojo.richtext.TiptapNode;
import com.arte.app.service.richtext.TiptapJsonParser;
import com.arte.app.strategy.richtext.NodeHandlerStrategy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 图片、音频、视频节点：不独立分块，以媒体引用形式拼入相邻文本块。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/22
 */
@Service
public class MediaNodeHandler implements NodeHandlerStrategy {
    private static final Set<String> SUPPORTED_TYPES = Set.of("image", "audio", "video");

    @Override
    public boolean supports(String nodeType) {
        return SUPPORTED_TYPES.contains(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        String mediaRef = MediaRefUtil.buildRef(node);
        if (!context.isBufferEmpty()) {
            context.getChunkBuffer().getLast().appendMediaRef(mediaRef);
            return;
        }

        // 媒体引用会在构建 chunk 时写入 content，此处不重复写入 text。
        context.addToBuffer(new ChunkingContext.BufferItem(
                "",
                node.getAttrString("id"),
                null, null, false,
                List.of(mediaRef)
        ));
    }
}
