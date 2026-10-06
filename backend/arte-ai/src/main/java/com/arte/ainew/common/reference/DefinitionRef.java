package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * 固定版本的定义引用
 * <p>
 * 固定版本的配置或契约定义引用，通过 type、id、version 定位指定定义版本。
 * 引用不携带定义内容，也不授予访问或执行权限；解析时仍须校验存在性和使用资格。
 *
 * @param type 定义类型，例如 capability、binding、connection、rate、schema
 * @param id 所属定义类型中的稳定标识，例如 text-generation、default-binding
 * @param version 固定版本标识，例如 v1；对应版本内容应保持不可变，禁止使用 latest
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
