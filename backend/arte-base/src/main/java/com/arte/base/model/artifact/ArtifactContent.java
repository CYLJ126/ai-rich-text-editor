package com.arte.base.model.artifact;

import com.arte.base.validation.ContractChecks;

import java.io.IOException;
import java.io.InputStream;

/**
 * 经提供者校验元数据和完整性的读取流；调用方必须关闭。
 */
public record ArtifactContent(Artifact artifact, InputStream content) implements AutoCloseable {
    public ArtifactContent {
        artifact = ContractChecks.required(artifact, "artifact");
        content = ContractChecks.required(content, "content");
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}
