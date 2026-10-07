package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 执行查询选择参数；身份、权限及读取期限由服务器解析。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:24 ✾
 */
public final class InvocationRequests {

    private InvocationRequests() {
    }

    public record Query(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String invocationId) {
    }

    /**
     * afterSequence 是排他游标；0 从首个保留事件开始，每页最多 256 条。
     */
    public record Replay(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String invocationId,
                         @NotNull @Min(0) Long afterSequence,
                         @NotNull @Min(1) @Max(256) Integer limit) {
    }
}
