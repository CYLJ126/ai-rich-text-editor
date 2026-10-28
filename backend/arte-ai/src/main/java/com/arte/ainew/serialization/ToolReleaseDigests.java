package com.arte.ainew.serialization;

import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Collection;
import java.util.Comparator;

/**
 * 固定发布契约摘要 v1；不含活动状态、操作者、审核时间或当前授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public final class ToolReleaseDigests {
    
    private ToolReleaseDigests() {
    }

    /**
     * @param tool     固定产品定义及执行目标。
     * @param provider 实际固定提供方接入契约。
     * @param schemas  输入／输出／配置依赖去重后的完整 Schema 集合。
     * @return 与来源顺序无关的 SHA-256 契约摘要。
     */
    public static String contract(ToolDefinitionRevision tool, ToolProviderDefinition provider, Collection<ToolSchemaDefinition> schemas) {
        var order = Comparator.comparing(DefinitionRef::type).thenComparing(DefinitionRef::id).thenComparing(DefinitionRef::version);
        var fields = CanonicalJson.fields("format", "tool-release-contract-v1", "tool", tool.tool().definition(),
                "checksum", tool.tool().checksum(), "provider", provider, "target", tool.target(),
                "schemas", schemas.stream().sorted(Comparator.comparing(ToolSchemaDefinition::definition, order))
                        .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList());
        if (tool.protocol() != null) fields.put("protocol", tool.protocol());
        return CanonicalJson.digest(fields);
    }
}
