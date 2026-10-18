package com.arte.ainew.web;

import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolBindingReceipt;
import com.arte.ainew.pojo.tool.ToolReleaseReceipt;
import com.arte.ainew.web.response.ToolManagementResponses;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

/**
 * 原始领域拒绝保留安全回执，HTTP 状态不把首次记录的失败误报为成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolManagementHttpResult {

    private ToolManagementHttpResult() {
    }

    public static ResponseEntity<ResultContext<ToolManagementResponses.BindingCommand>> binding(StoreOutcome<ToolBindingReceipt> outcome, Locale locale) {
        String reason = outcome.value() == null ? outcome.code().name() : outcome.value().result().rejection().name();
        return command(ToolManagementResponses.bindingCommand(outcome), reason, locale);
    }

    public static ResponseEntity<ResultContext<ToolManagementResponses.ReleaseCommand>> release(StoreOutcome<ToolReleaseReceipt> outcome, Locale locale) {
        String reason = outcome.value() == null ? outcome.code().name() : outcome.value().rejection().name();
        return command(ToolManagementResponses.releaseCommand(outcome), reason, locale);
    }

    private static <T> ResponseEntity<ResultContext<T>> command(T value, String reason, Locale locale) {
        if ("NONE".equals(reason))
            return ResponseEntity.ok(ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
        var code = switch (reason) {
            case "IDEMPOTENCY_CONFLICT" -> ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT;
            case "VERSION_CONFLICT" -> ResultCodeEnum.AI_VERSION_CONFLICT;
            case "NOT_FOUND", "TOOL_NOT_FOUND", "OWNER_MISMATCH" -> ResultCodeEnum.AI_NOT_FOUND;
            case "CONFIGURATION_INVALID" -> ResultCodeEnum.AI_TOOL_CONFIGURATION_INVALID;
            case "POLICY_CONFLICT", "NETWORK_NOT_ALLOWED" -> ResultCodeEnum.AI_TOOL_POLICY_DENIED;
            case "SECRET_UNSUPPORTED" -> ResultCodeEnum.AI_TOOL_SECRET_UNSUPPORTED;
            case "PREVIEW_CHANGED" -> ResultCodeEnum.AI_TOOL_PLAN_CHANGED;
            case "TOOL_UNAVAILABLE", "BINDING_DISABLED", "EXPIRED" -> ResultCodeEnum.AI_TOOL_NOT_AVAILABLE;
            case "EXECUTOR_UNAVAILABLE", "CHAIN_INVALID", "CHAIN_LIMIT_EXCEEDED" ->
                    ResultCodeEnum.AI_TOOL_MANAGEMENT_UNAVAILABLE;
            default -> ResultCodeEnum.AI_INVALID_STATE;
        };
        var status = switch (code) {
            case AI_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AI_TOOL_CONFIGURATION_INVALID, AI_TOOL_POLICY_DENIED, AI_TOOL_SECRET_UNSUPPORTED ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_TOOL_MANAGEMENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(ResultContext.<T>fail(code, locale).setData(value));
    }
}
