package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 只接受读取意图；身份、运行输入和子调用引用均从服务端持久化记录取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public final class ToolChatRequests {

    private ToolChatRequests() {
    }

    public record Query(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String runId) implements ToolManagementRequests.Strict {
    }

    public record Events(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String runId,
                         @Min(0) long afterSequence,
                         @Min(1) @Max(100) int limit) implements ToolManagementRequests.Strict {
    }
}
