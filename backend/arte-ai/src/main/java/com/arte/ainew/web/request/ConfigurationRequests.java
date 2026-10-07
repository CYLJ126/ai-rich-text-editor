package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 发现只选择租户／空间，身份及权限始终从可信 HTTP 上下文取得。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
public final class ConfigurationRequests {

    private ConfigurationRequests() {
    }

    public record DiscoverChatOptions(@NotNull @Valid ConversationRequests.Scope scope) {
    }
}
