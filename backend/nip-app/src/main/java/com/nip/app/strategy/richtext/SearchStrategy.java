package com.nip.app.strategy.richtext;

import com.nip.app.pojo.richtext.ChunkDocument;
import com.nip.core.es.EsSearchResponse;

/**
 * 搜索策略接口 —— 支持全文、向量、混合搜索扩展
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:55 ✾
 */
public interface SearchStrategy {

    EsSearchResponse<ChunkDocument> search(Object request);

    boolean supports(Class<?> requestType);
}
