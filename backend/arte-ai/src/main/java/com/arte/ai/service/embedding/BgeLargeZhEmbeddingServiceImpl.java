package com.arte.ai.service.embedding;

import com.arte.ai.api.EmbeddingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <a href="https://huggingface.co/BAAI/bge-large-zh-v1.5">BAAI/bge-large-zh-v1.5</a>
 * BAAI/bge-large-zh-v1.5 嵌入向量
 * 维度：1024
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 17:17 ✾
 **/
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.ai.embedding.provider", havingValue = "bge-large", matchIfMissing = true)
public class BgeLargeZhEmbeddingServiceImpl implements EmbeddingService {

    @Resource
    private EmbeddingModel embeddingModel;

    @Override
    public int getDimension() {
        return embeddingModel.dimensions();
    }

    @Override
    public float[] generateEmbedding(String text) {
        EmbeddingResponse response = embeddingModel.call(
                new EmbeddingRequest(List.of(text), EmbeddingOptions.builder().build())
        );
        return response.getResults().getFirst().getOutput();
    }

    @PostConstruct
    public void init() {
        log.info("BAAI/bge-large-zh-v1.5 嵌入向量模型维度：{}", getDimension());
    }
}
