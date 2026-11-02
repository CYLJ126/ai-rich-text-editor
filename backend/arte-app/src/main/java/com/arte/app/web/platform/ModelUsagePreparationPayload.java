package com.arte.app.web.platform;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用于定义模型使用准备接口接收的请求参数
 * <p>
 * 外层类用于组织请求类型，实际接收 JSON 的是内部 Request。
 * 目标是实际聊天执行空间；不接受预算金额、主体、连接或凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
public final class ModelUsagePreparationPayload {

    private ModelUsagePreparationPayload() {
    }

    /**
     * HTTP 请求契约
     *
     * @param scope      实际聊天执行的租户和工作空间
     * @param resourceId 稳定模型资源 ID
     * @param binding    要使用的精确模型绑定版本
     */
    public record Request(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                          @NotNull DefinitionRef binding) implements StrictPlatformRequest {
    }
}
