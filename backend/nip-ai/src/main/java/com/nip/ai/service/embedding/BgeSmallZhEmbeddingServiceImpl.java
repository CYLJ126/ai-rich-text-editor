package com.nip.ai.service.embedding;

import com.nip.ai.api.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * <a href="https://huggingface.co/BAAI/bge-small-zh-v1.5">BAAI/bge-small-zh-v1.5</a>
 * Langchain4j 嵌入向量服务实现类
 * 嵌入维度：512
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:43 ✾
 **/
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.ai.embedding.provider", havingValue = "bge-small")
public class BgeSmallZhEmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel model = new BgeSmallZhV15EmbeddingModel();

    @Override
    public int getDimension() {
        return model.dimension();
    }

    @Override
    public float[] generateEmbedding(String text) {
        Embedding embedding = model.embed(text).content();
        return embedding.vector();
    }

    @PostConstruct
    public void init() {
        log.info("BAAI/bge-small-zh-v1.5 嵌入向量模型维度：{}", getDimension());
    }
}
