package com.arte.core.es;

import java.util.List;
import java.util.Map;

/**
 * 通用搜索响应
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:21 ✾
 **/
public record EsSearchResponse<T>(
        List<Hit<T>> hits,
        long totalHits,
        int page,
        int size,
        boolean hasMore
) {
    public record Hit<T>(
            String id,
            float score,
            T source,
            Map<String, List<String>> highlights  // 高亮片段
    ) {
    }

    public static <T> EsSearchResponse<T> empty() {
        return new EsSearchResponse<>(List.of(), 0L, 0, 0, false);
    }

    public static boolean isEmpty(EsSearchResponse<?> response) {
        return response == null || response.hits() == null || response.hits().isEmpty();
    }
}
