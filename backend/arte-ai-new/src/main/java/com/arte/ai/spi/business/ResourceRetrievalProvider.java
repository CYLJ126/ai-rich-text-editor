package com.arte.ai.spi.business;

import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceRetrievalRequest;
import com.arte.base.model.execution.ExecutionContext;
import java.util.List;

/** 业务端口：提供者负责先授权再读取，返回固定版本和实际内容的来源；不依赖 app DTO。 */
public interface ResourceRetrievalProvider {
    List<ContextFragment> retrieve(ExecutionContext viewer, ResourceRetrievalRequest selection, String query);
}
