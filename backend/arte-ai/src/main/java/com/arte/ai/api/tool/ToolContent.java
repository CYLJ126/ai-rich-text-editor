package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolContentTypeEnum;

import java.net.URI;
import java.util.Map;

/**
 * 返回给模型或用户的上下文内容块。
 * <p>
 * 不同内容形态使用独立 record，避免在单一对象中混合 text、uri 等大量可空字段。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public sealed interface ToolContent permits ToolContent.Text, ToolContent.Structured,
        ToolContent.Media, ToolContent.ResourceLink {

    ToolContentTypeEnum type();

    Map<String, Object> metadata();

    record Text(String text, Map<String, Object> metadata) implements ToolContent {

        public Text {
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
            metadata = immutable(metadata);
        }

        @Override
        public ToolContentTypeEnum type() {
            return ToolContentTypeEnum.TEXT;
        }
    }

    record Structured(Map<String, Object> value, Map<String, Object> metadata) implements ToolContent {

        public Structured {
            value = immutable(value);
            metadata = immutable(metadata);
        }

        @Override
        public ToolContentTypeEnum type() {
            return ToolContentTypeEnum.STRUCTURED;
        }
    }

    record Media(
            ToolContentTypeEnum type,
            String mediaType,
            URI uri,
            Map<String, Object> metadata
    ) implements ToolContent {

        public Media {
            if (type != ToolContentTypeEnum.IMAGE && type != ToolContentTypeEnum.AUDIO) {
                throw new IllegalArgumentException("media type must be IMAGE or AUDIO");
            }
            if (mediaType == null || mediaType.isBlank() || uri == null) {
                throw new IllegalArgumentException("mediaType and uri are required");
            }
            metadata = immutable(metadata);
        }
    }

    record ResourceLink(URI uri, String mediaType, Map<String, Object> metadata) implements ToolContent {

        public ResourceLink {
            if (uri == null) {
                throw new IllegalArgumentException("uri must not be null");
            }
            metadata = immutable(metadata);
        }

        @Override
        public ToolContentTypeEnum type() {
            return ToolContentTypeEnum.RESOURCE_LINK;
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null ? Map.of() : Map.copyOf(source);
    }
}
