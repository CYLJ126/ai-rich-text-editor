package com.arte.app.platform.workspace;

import com.arte.core.pojo.workspace.WorkspaceBrowseBudget;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "arte.platform.workspace.directory", ignoreUnknownFields = false)
public record WorkspaceDirectoryProperties(Integer batchSize, Integer maxCandidates, Integer maxBatches,
                                           Duration maxWorkTime) {

    public WorkspaceDirectoryProperties {
        batchSize = batchSize == null ? 100 : batchSize;
        maxCandidates = maxCandidates == null ? 200 : maxCandidates;
        maxBatches = maxBatches == null ? 4 : maxBatches;
        maxWorkTime = maxWorkTime == null ? Duration.ofSeconds(1) : maxWorkTime;
        new WorkspaceBrowseBudget(batchSize, maxCandidates, maxBatches, maxWorkTime);
    }

    public WorkspaceBrowseBudget budget() {
        return new WorkspaceBrowseBudget(batchSize, maxCandidates, maxBatches, maxWorkTime);
    }
}
