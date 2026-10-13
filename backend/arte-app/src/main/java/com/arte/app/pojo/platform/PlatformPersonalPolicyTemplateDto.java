package com.arte.app.pojo.platform;

import lombok.Data;

/**
 * 不可变模板修订的数据库映射
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
@Data
public class PlatformPersonalPolicyTemplateDto {
    private String rules;
    private Boolean membersMayCreateWorkspace;
    private Integer policySchemaVersion;
    private Integer rolePresetVersion;
    private Long version;
}
