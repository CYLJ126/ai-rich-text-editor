package com.arte.base.model.admission;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

/**
 * 租户、工作类型及适用供应商／固定连接的准入分区；不携带凭据或 URL。
 */
public record AdmissionKey(String tenantId, String workload, String provider, ResourceRef connection) {
    public AdmissionKey {
        tenantId = ContractChecks.identifier(tenantId, "tenantId");
        workload = ContractChecks.identifier(workload, "workload");
        provider = ContractChecks.optionalIdentifier(provider, "provider");
        if (connection != null && (connection.version() == null || connection.isDraft() || connection.rangeRef() != null)) {
            throw new IllegalArgumentException("connection must identify a saved connection");
        }
    }
}
