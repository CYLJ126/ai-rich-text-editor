package com.arte.ai.api;

/**
 * 嵌入向量服务接口
 * <p>
 * 门面，屏蔽模型差异
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 16:42 ✾
 **/
public interface EmbeddingService {

    /**
     * r返回当前嵌入模型支持的向量维度
     *
     * @return 向量维度
     */
    int getDimension();

    /**
     * 将文本转化为向量
     *
     * @param text 原始文本
     * @return 嵌入向量
     */
    float[] generateEmbedding(String text);
}
