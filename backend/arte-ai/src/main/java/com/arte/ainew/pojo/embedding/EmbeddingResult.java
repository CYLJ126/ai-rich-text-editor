package com.arte.ainew.pojo.embedding;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 向量结果与聊天输出分开；spaceId 由固定模型、维度和预处理版本共同确定，不同空间不能混用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record EmbeddingResult(String spaceId, DefinitionRef model, int dimensions,
                              List<Vector> vectors, Usage usage) implements Serializable {
    public record Vector(String inputId, List<Float> values) implements Serializable {
        public Vector {
            ContractChecks.id(inputId, "inputId");
            values = ContractChecks.list(values, "values", 1, 65_536);
            ContractChecks.require(values.stream().allMatch(Float::isFinite), "Vector must contain finite values");
        }
    }

    public EmbeddingResult {
        ContractChecks.id(spaceId, "spaceId");
        Objects.requireNonNull(model, "model").requireType("model");
        ContractChecks.range(dimensions, "dimensions", 1, 65_536);
        vectors = ContractChecks.list(vectors, "vectors", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(vectors.stream().map(Vector::inputId).toList(), "vector input IDs");
        final int dimension = dimensions;
        ContractChecks.require(vectors.stream().allMatch(vector -> vector.values().size() == dimension), "Vector dimension mismatch");
        ContractChecks.require(vectors.stream().mapToLong(vector -> vector.values().size()).sum() <= 1_000_000,
                "Embedding result exceeds total vector capacity");
        Objects.requireNonNull(usage, "usage");
    }

    /**
     * 由执行层在保存结果前调用，输出顺序须与输入一致，避免索引关联错位。
     */
    public void validateAgainst(EmbeddingRequest request) {
        ContractChecks.require(vectors.stream().map(Vector::inputId).toList()
                .equals(Objects.requireNonNull(request, "request").inputs().stream()
                        .map(EmbeddingRequest.Input::inputId).toList()), "Embedding output does not match requested inputs");
    }
}
