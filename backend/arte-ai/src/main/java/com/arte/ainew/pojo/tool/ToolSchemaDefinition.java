package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;

import java.io.Serializable;
import java.util.Objects;

/**
 * 固定 Schema 原文及内容摘要
 * <p>
 * 构造只做结构检查，存储边界验证子集和摘要真实性。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public record ToolSchemaDefinition(DefinitionRef definition, StructuredValue.ObjectValue document,
                                   String checksum) implements Serializable {

    public ToolSchemaDefinition {
        Objects.requireNonNull(definition, "definition").requireType("schema");
        Objects.requireNonNull(document, "document");
        ContractChecks.digest(checksum, "checksum");
    }
}
