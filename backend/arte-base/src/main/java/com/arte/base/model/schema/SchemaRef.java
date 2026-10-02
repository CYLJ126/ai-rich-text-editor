package com.arte.base.model.schema;

import com.arte.base.validation.ContractChecks;

/**
 * 固定到版本的 Schema 引用；标识与版本均必填，Schema 解析和内容校验由提供者完成。
 */
public record SchemaRef(String schemaId, String version) {

    public SchemaRef {
        schemaId = ContractChecks.identifier(schemaId, "schemaId");
        version = ContractChecks.identifier(version, "version");
    }
}
