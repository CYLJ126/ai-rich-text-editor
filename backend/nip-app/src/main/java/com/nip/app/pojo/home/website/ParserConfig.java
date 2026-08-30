package com.nip.app.pojo.home.website;

import lombok.Data;

import java.util.List;

/**
 * @author zhangsc
 * @since 2025/4/10 14:13
 */
@Data
public class ParserConfig {
    private ConditionConfig condition;
    private String dataPath;
    private List<FieldMapping> mappings;
}
