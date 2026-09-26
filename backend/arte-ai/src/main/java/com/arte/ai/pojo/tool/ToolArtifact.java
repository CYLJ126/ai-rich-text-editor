package com.arte.ai.pojo.tool;

import java.net.URI;
import java.util.Map;

/**
 * 工具生成的可持久化产物，例如文件、报告或导出结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolArtifact(
        String id,
        String name,
        String mediaType,
        URI uri,
        Map<String, Object> metadata
) {

    public ToolArtifact {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
