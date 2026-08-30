package com.arte.app.pojo.home.website;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author zhangsc
 * @since 2025/4/10 14:13
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldMapping {
    /**
     * json 中的字段名
     */
    private String sourcePath;
    /**
     * {@link NewsVo 中的字段名}
     */
    private String targetField;

}
