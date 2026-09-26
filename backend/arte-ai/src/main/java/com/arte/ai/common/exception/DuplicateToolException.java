package com.arte.ai.common.exception;

import com.arte.ai.pojo.tool.ToolReference;

import java.io.Serial;

/**
 * 工具重复注册异常
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public class DuplicateToolException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 7746205246778917745L;

    public DuplicateToolException(ToolReference reference) {
        super("tool already registered: " + reference.namespace() + ":"
                + reference.name() + ":" + reference.version());
    }
}
