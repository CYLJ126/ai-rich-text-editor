package com.arte.base.model.error;

/**
 * 稳定错误码的扩展端口。领域可用自己的枚举实现，不以显示文案或 HTTP 状态作为错误码。
 */
@FunctionalInterface
public interface ErrorCode {

    String code();
}
