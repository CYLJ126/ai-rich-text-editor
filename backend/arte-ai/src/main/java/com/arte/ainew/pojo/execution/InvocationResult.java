package com.arte.ainew.pojo.execution;

import com.arte.ainew.pojo.embedding.EmbeddingResult;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.media.MediaResult;
import com.arte.ainew.pojo.remote.RemoteApplicationResult;
import com.arte.ainew.pojo.tool.ToolResult;

import java.io.Serializable;
import java.util.Objects;

/**
 * 结果字节存储使用的封闭类型信封；Pending 不是已完成结果。类型别名／Schema 由受信编码注册表决定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public sealed interface InvocationResult extends Serializable permits InvocationResult.Generation,
        InvocationResult.Embedding, InvocationResult.Tool, InvocationResult.Media, InvocationResult.RemoteApplication {
    record Generation(ModelResult value) implements InvocationResult {
        public Generation {
            Objects.requireNonNull(value, "value");
        }
    }

    record Embedding(EmbeddingResult value) implements InvocationResult {
        public Embedding {
            Objects.requireNonNull(value, "value");
        }
    }

    record Tool(ToolResult value) implements InvocationResult {
        public Tool {
            Objects.requireNonNull(value, "value");
        }
    }

    record Media(MediaResult.Completed value) implements InvocationResult {
        public Media {
            Objects.requireNonNull(value, "value");
        }
    }

    record RemoteApplication(RemoteApplicationResult.Completed value) implements InvocationResult {
        public RemoteApplication {
            Objects.requireNonNull(value, "value");
        }
    }
}
