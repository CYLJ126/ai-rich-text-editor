package com.arte.ainew.web.request;

import com.arte.core.pojo.PageParam;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.reference.ResourceVersionRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * AI 会话管理 HTTP 接口的请求参数容器。
 * <p>
 * 集中定义会话创建、改名、置顶、命令回执、目录及历史查询所需的请求对象，并声明基础字段校验约束。
 * scope 只用于指定租户与工作空间范围，实际访问资格由服务端结合当前登录身份核验，
 * 不接受客户端声明的 owner 或权限；版本、幂等和业务支持范围由对应服务进一步检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
public final class ConversationRequests {

    private ConversationRequests() {
    }

    /**
     * 请求操作的租户与工作空间范围；仅用于定位数据，不代表已取得访问权限。
     *
     * @param tenantId    目标租户标识
     * @param workspaceId 目标工作空间标识
     */
    public record Scope(@NotBlank @Size(max = 256) String tenantId,
                        @NotBlank @Size(max = 256) String workspaceId) {
    }

    /**
     * 创建指定标题会话的请求，可携带聊天配置及资料引用。
     * <p>
     * 幂等键通过 Idempotency-Key 请求头传入；profile/资料仍由现有 Service 检查支持范围。
     */
    public record Create(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String title,
                         DefinitionRef chatProfile, @Size(max = 256) List<ResourceVersionRef> resources) {
    }

    /**
     * 创建默认会话的请求，可提供客户端本地日期，由服务端按默认会话规则处理。
     */
    public record CreateDefault(@NotNull @Valid Scope scope,
                                @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String localDate) {
    }

    /**
     * 修改会话标题的请求，使用预期元数据版本防止覆盖并发修改。
     */
    public record Rename(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String conversationId,
                         @NotNull @Min(0) Long expectedMetadataVersion, @NotBlank @Size(max = 256) String title) {
    }

    /**
     * 设置或取消会话置顶的请求，使用预期元数据版本防止覆盖并发修改。
     */
    public record SetPinned(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String conversationId,
                            @NotNull @Min(0) Long expectedMetadataVersion, @NotNull Boolean pinned) {
    }

    /**
     * 按命令标识查询会话元数据操作回执的请求，用于确认改名或置顶等操作结果。
     */
    public record ReceiptQuery(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String commandId) {
    }

    /**
     * 按置顶分组和游标查询会话目录的请求，可选择是否返回总数。
     */
    public record CursorQuery(@NotNull @Valid Scope scope, @NotNull Boolean pinned,
                              @Min(1) @Max(20) int size, @Size(max = 2048) String cursor, Boolean includeTotal) {
    }

    /**
     * 查询包含置顶区和普通区的会话目录窗口，可指定锚点会话恢复浏览位置。
     */
    public record WindowQuery(@NotNull @Valid Scope scope, @Min(1) @Max(100) int normalSize,
                              @Size(max = 256) String anchorConversationId) {
    }

    /**
     * 按当前范围和会话标识查询会话详情或元数据的请求。
     */
    public record Find(@NotNull @Valid Scope scope, @NotBlank @Size(max = 256) String conversationId) {
    }

    /**
     * 按页码查询会话列表的请求，只组合当前支持的分页条件，避免默默忽略 BaseParam 中尚无对应数据库列的筛选条件。
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

    /**
     * 按指定会话和预期会话版本分页查询轮次历史的请求。
     */
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
