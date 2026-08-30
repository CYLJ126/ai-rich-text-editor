package com.arte.core.es;

import java.util.List;
import java.util.Map;

/**
 * 全文搜索请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:20 ✾
 **/
public record EsSearchRequest(String query,
                              Integer page,
                              Integer size,
                              Map<String, Object> filters,  // 过滤条件，key=字段名，value=期望值
                              List<String> includeFields,   // 返回字段白名单，null 表示全部
                              SortOption sort
) {
    public record SortOption(String field, boolean ascending) {
    }

    public static EsSearchRequest of(String query) {
        return new EsSearchRequest(query, 0, 20, Map.of(), null, null);
    }
}
