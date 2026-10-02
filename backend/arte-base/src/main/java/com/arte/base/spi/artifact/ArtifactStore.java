package com.arte.base.spi.artifact;

import com.arte.base.model.artifact.*;
import com.arte.base.model.identity.ExecutionScope;

import java.io.InputStream;
import java.util.Optional;

/**
 * 通用文件产物存储。
 *
 * <p>管理上传、读取、完整性、归属及生命周期；负责文件字节，附件关系和业务应用状态由所属领域管理。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * scope 必须来自已验证的服务端上下文；私有字节按完整 scope 隔离，不接收网络位置或公开下载地址。
 * 这是内部存储端口；领域授权、分享、下载授权和文件验证由所属服务在操作边界落实。
 */
public interface ArtifactStore {
    /**
     * 先完整接收、校验大小和摘要，返回 QUARANTINED；不因上传成功自动开放下载。
     */
    Artifact upload(ArtifactUpload request, InputStream bytes);

    Optional<Artifact> find(ExecutionScope scope, String artifactId);

    ArtifactContent open(ExecutionScope scope, ArtifactRef expected);

    /**
     * 仅由可信验证／生命周期服务调用，expectedRevision 失配必须拒绝。
     */
    Artifact transition(ExecutionScope scope, String artifactId, long expectedRevision, ArtifactStatus next);

    void delete(ExecutionScope scope, String artifactId, long expectedRevision);
}
