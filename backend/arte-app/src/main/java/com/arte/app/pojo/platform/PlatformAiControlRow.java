package com.arte.app.pojo.platform;

import lombok.Data;

/**
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
@Data
public class PlatformAiControlRow {
    private String tenantId;
    private String workspaceId;
    private String subjectId;
    private String budgetRef;
    private String payload;
    private Long version;
}
