package com.arte.ai.pojo.tool;

/**
 * 发布时明确兼容基准和升级说明；没有兼容基准则不自动升级用户绑定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
public record ToolPublishCommand(String compatibilityBaseVersion, String releaseNotes) {
    public ToolPublishCommand {
        compatibilityBaseVersion = normalize(compatibilityBaseVersion);
        releaseNotes = normalize(releaseNotes);
        if (releaseNotes != null && releaseNotes.length() > 2000) {
            throw new IllegalArgumentException("releaseNotes must not exceed 2000 characters");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
