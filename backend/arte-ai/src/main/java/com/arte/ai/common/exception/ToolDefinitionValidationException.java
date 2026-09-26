package com.arte.ai.common.exception;

import lombok.Getter;

import java.io.Serial;
import java.util.List;

/**
 * 工具定义校验异常
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
public class ToolDefinitionValidationException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = -7455548816219355711L;
    private final List<String> violations;

    public ToolDefinitionValidationException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

}
