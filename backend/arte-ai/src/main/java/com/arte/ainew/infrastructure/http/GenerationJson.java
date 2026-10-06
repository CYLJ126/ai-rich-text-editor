package com.arte.ainew.infrastructure.http;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 独立有界 JSON 编解码器，不继承旧 AI 或全局 HTTP 的序列化定制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class GenerationJson {

    private GenerationJson() {
    }

    public static JsonMapper mapper(int maxFrameBytes) {
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(maxFrameBytes).maxNumberLength(64).build()).build();
        return JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    }
}
