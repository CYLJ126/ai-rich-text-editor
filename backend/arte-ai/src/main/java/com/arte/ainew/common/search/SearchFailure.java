package com.arte.ainew.common.search;

import java.io.Serial;

/**
 * 会话搜索的内部业务异常，通过稳定错误码区分会话失效、资源限制及依赖故障等情况。
 * 异常消息只包含错误码，供 HTTP 层统一映射响应。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 3378631350222783122L;

    /**
     * 搜索错误分类，用于统一识别失败原因及决定前端处理方式。
     */
    public enum Code {
        GROUP_FILTER_NOT_AVAILABLE, ARCHIVE_NOT_AVAILABLE, DEPENDENCY_UNAVAILABLE, INCOMPLETE_RESPONSE,
        SEARCH_EXPIRED, SEARCH_LIMIT_REACHED, SEARCH_NOT_ENABLED, INVALID_CURSOR, SEARCH_BUSY
    }

    private final Code code;

    /**
     * 创建仅携带搜索错误码的异常。
     */
    public SearchFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    /**
     * 保留底层异常原因，同时以搜索错误码作为对外映射依据。
     */
    public SearchFailure(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    /**
     * 返回稳定的搜索错误分类。
     */
    public Code code() {
        return code;
    }
}
