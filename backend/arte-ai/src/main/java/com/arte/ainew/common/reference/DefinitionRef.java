package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * 固定版本的定义引用；version 为不可变版本标识，禁止通过 latest 暗中改变运行配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record DefinitionRef(String type, String id, String version) implements Serializable {
    public DefinitionRef {
        ContractChecks.id(type, "type");
        ContractChecks.id(id, "id");
        ContractChecks.id(version, "version");
        ContractChecks.require(!version.equalsIgnoreCase("latest"), "Definition version must be fixed");
    }

    public DefinitionRef requireType(String expected) {
        ContractChecks.require(type.equals(expected), "Expected definition type: " + expected);
        return this;
    }
}
