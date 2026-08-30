package com.nip.app.strategy.richtext;

import com.nip.app.pojo.richtext.ChunkingContext;
import com.nip.app.pojo.richtext.TiptapNode;
import com.nip.app.service.richtext.TiptapJsonParser;

/**
 * 节点处理策略接口
 * 每种 Tiptap 节点类型对应一个实现
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:01 ✾
 **/
public interface NodeHandlerStrategy {

    /**
     * 是否支持处理该节点类型
     */
    boolean supports(String nodeType);

    /**
     * 处理节点，结果写入 context
     *
     * @param node    当前节点
     * @param context 分块上下文
     * @param parser  解析器引用（用于递归处理子节点）
     */
    void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser);
}
