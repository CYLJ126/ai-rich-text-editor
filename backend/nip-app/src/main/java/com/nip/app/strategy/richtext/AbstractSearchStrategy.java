package com.nip.app.strategy.richtext;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.nip.app.pojo.richtext.ChunkDocument;
import com.nip.core.es.EsSearchResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽象搜索策略 —— 提供通用的搜索方法和工具类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 11:41 ✾
 **/
public abstract class AbstractSearchStrategy implements SearchStrategy {

    /**
     * Object -> FieldValue 显式类型转换。
     * ES Java Client 9.x 移除了 FieldValue.anyValue(Object)，
     * 必须按实际类型分支处理，兜底降级为 toString()。
     */
    protected static FieldValue toFieldValue(Object value) {
        return switch (value) {
            case null -> FieldValue.NULL;
            case Boolean b -> FieldValue.of(b);
            case Long l -> FieldValue.of(l);
            case Integer i -> FieldValue.of((long) i);
            case Double d -> FieldValue.of(d);
            case Float f -> FieldValue.of((double) f);
            case String s -> FieldValue.of(s);
            default -> FieldValue.of(value.toString());
        };
    }

    protected List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    /**
     * Hit 映射
     *
     * @param hit 搜索结果 Hit
     * @return 映射后的 Hit
     */
    protected EsSearchResponse.Hit<ChunkDocument> mapHit(Hit<ChunkDocument> hit) {
        Map<String, List<String>> highlights = new HashMap<>();
        if (hit.highlight() != null) {
            highlights.putAll(hit.highlight());
        }
        return new EsSearchResponse.Hit<>(
                hit.id(),
                hit.score() != null ? hit.score().floatValue() : 0f,
                hit.source(),
                highlights);
    }

}
