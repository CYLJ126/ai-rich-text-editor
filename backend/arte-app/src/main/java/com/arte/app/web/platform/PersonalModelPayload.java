package com.arte.app.web.platform;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 个人模型发布的严格请求契约
 * <p>
 * 不接受地址、SecretRef、预算金额、主体或自报发布内容。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class PersonalModelPayload {

    private PersonalModelPayload() {
    }

    /**
     * 查询当前可发布的审核方案。
     *
     * @param scope      模型主空间
     * @param resourceId 稳定模型资源 ID
     */
    public record OptionsRequest(@NotNull Scope scope,
                                 @NotBlank @Size(max = 256) String resourceId) implements StrictPlatformRequest {
    }

    /**
     * 固定依赖并发布新版本，不创建预算或自动调用供应商。
     *
     * @param scope             模型主空间
     * @param resourceId        稳定模型资源 ID
     * @param expectedVersion   当前模型管理版本
     * @param draftVersion      当前草稿版本
     * @param credentialVersion 本人的当前凭据代次
     * @param validationId      本人成功且十分钟内的验证 ID
     * @param planRef   方案接口返回的精确 model-plan 引用
     * @param catalogRevision   方案接口返回的审核目录修订
     */
    public record PublishRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                 @NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long draftVersion,
                                 @NotNull @Min(1) Long credentialVersion,
                                 @NotBlank @Size(max = 256) String validationId,
                                 @NotNull DefinitionRef planRef,
                                 @NotNull @Min(1) Long catalogRevision) implements StrictPlatformRequest {
    }

    /**
     * 查询精确个人版本的当前准备条件。
     *
     * @param scope      模型主空间
     * @param resourceId 稳定模型资源 ID
     * @param binding    精确个人绑定，不使用 latest
     */
    public record StatusRequest(@NotNull Scope scope, @NotBlank @Size(max = 256) String resourceId,
                                @NotNull DefinitionRef binding) implements StrictPlatformRequest {
    }
}
