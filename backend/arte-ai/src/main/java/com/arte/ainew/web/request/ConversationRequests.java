package com.arte.ainew.web.request;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.core.pojo.PageParam;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * HTTP 选择参数；scope 必须由授权解析器验证，不接受 owner 或客户端声明的权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
public final class ConversationRequests {

    private ConversationRequests() {
    }

    public record Scope(@NotBlank @Size(max = 256) String tenantId,
                        @NotBlank @Size(max = 256) String workspaceId) {
    }

    /**
     * 幂等键通过 Idempotency-Key 请求头传入；profile/资料仍由现有 Service 检查支持范围。
     */
    public record Create(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String title,
                         DefinitionRef chatProfile, @Size(max = 256) List<ResourceRef> resources) {
    }

    public record Find(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String conversationId) {
    }

    /**
     * 只组合当前支持的分页条件，避免默默忽略 BaseParam 中尚无对应数据库列的筛选条件。
     */
    @Getter
    @Setter
    public static class ListQuery {
        @NotNull
        @Valid
        private Scope scope;
        @NotNull
        @Valid
        private PageParam page = new PageParam();
    }

    @Getter
    @Setter
    public static class TurnsQuery extends ListQuery {
        @NotBlank
        @Size(max = 256)
        private String conversationId;
        /**
         * 从会话详情取得，版本变化时需刷新详情后重新查询。
         */
        @NotNull
        @Min(0)
        private Long expectedVersion;
    }
}
