package com.arte.ai.model.action;

import com.arte.ai.model.execution.ModelExecution;

/**
 * execution 为空表示输入已固定但尚未可靠受理；重放原请求可继续受理。
 */
public record AiActionResult(AiActionExecution action, ModelExecution execution) {
}
