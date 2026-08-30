package com.arte.app.strategy.richtext.handler;

import com.arte.app.pojo.richtext.ChunkingContext;
import com.arte.app.pojo.richtext.TiptapNode;
import com.arte.app.service.richtext.TiptapJsonParser;
import com.arte.app.strategy.richtext.NodeHandlerStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 水平分隔线：作为分块边界，触发 flush
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:06 ✾
 **/
@Service
@Slf4j
public class HorizontalRuleNodeHandler implements NodeHandlerStrategy {

    @Override
    public boolean supports(String nodeType) {
        return "horizontalRule".equals(nodeType);
    }

    @Override
    public void handle(TiptapNode node, ChunkingContext context, TiptapJsonParser parser) {
        parser.flushBuffer(context);
    }

}
