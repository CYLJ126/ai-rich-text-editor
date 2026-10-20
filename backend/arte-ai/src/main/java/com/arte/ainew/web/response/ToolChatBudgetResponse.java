package com.arte.ainew.web.response;

import com.arte.ainew.pojo.tool.ToolChatBudget;

/**
 * 按币种的预算安全投影；金额使用十进制字符串，负 available 表示真实费用已超额。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public record ToolChatBudgetResponse(String runId, String currency, String limit, String charged, String held,
                                     String available, boolean blocked, long version) {

    public static ToolChatBudgetResponse from(ToolChatBudget value) {
        return new ToolChatBudgetResponse(value.runId(), value.limit().currency().getCurrencyCode(), value.limit().amount().toPlainString(),
                value.charged().amount().toPlainString(), value.held().amount().toPlainString(), value.available().toPlainString(), value.blocked(), value.version());
    }
}
