package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 受理同事务保存的小型查询投影；不包含参数、配置、权限、凭据或输出内容。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public record ToolObservationMetadata(int schemaVersion, String invocationId, String toolId, String toolVersion,
                                      String bindingId, String title, String providerId, String billingMode,
                                      Long inputBytes) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ToolObservationMetadata {
        ContractChecks.require(schemaVersion == 1, "Unsupported tool observation version");
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(toolId, "toolId");
        ContractChecks.id(toolVersion, "toolVersion");
        ContractChecks.id(bindingId, "bindingId");
        ContractChecks.text(title, "title", 512);
        ContractChecks.id(providerId, "providerId");
        ContractChecks.require("FREE".equals(billingMode) || "CHARGE".equals(billingMode), "Invalid billing mode");
        if (inputBytes != null) ContractChecks.range(inputBytes, "inputBytes", 0, 100_000_000);
    }

    public static ToolObservationMetadata from(ToolExecutionSnapshot snapshot) {
        var tool = snapshot.binding().tool().tool();
        return new ToolObservationMetadata(1, snapshot.request().context().executionId(), tool.definition().id(), tool.definition().version(),
                snapshot.input().bindingId(), tool.title(), tool.providerId(), tool.price() == null ? "FREE" : "CHARGE",
                (long) CanonicalJson.encode(ToolCatalogDigests.jsonValue(snapshot.input().arguments())).getBytes(StandardCharsets.UTF_8).length);
    }

    public String encode() {
        return CanonicalJson.encode(this);
    }

    public static ToolObservationMetadata decode(String value) {
        ContractChecks.require(value != null && value.length() <= 16_384, "Invalid tool observation metadata");
        return JSON.readValue(value, ToolObservationMetadata.class);
    }
}
