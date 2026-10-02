package com.arte.base.validation;

import java.net.URI;
import java.util.Set;

/**
 * 值契约的结构校验；不读取外部状态，也不验证身份、授权或资源是否存在。
 * 校验失败只报告字段名及规则，不在异常消息中回显字段内容。
 */
public final class ContractChecks {

    private ContractChecks() {
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    /**
     * 标识保持原值；拒绝空白和控制字符，不静默修剪或改变大小写。
     */
    public static String identifier(String value, String field) {
        required(value, field);
        if (value.isEmpty() || value.codePoints().anyMatch(character ->
                Character.isWhitespace(character) || Character.isSpaceChar(character)
                        || Character.isISOControl(character))) {
            throw new IllegalArgumentException(field + " must be a non-blank identifier without whitespace or control characters");
        }
        return value;
    }

    public static String optionalIdentifier(String value, String field) {
        return value == null ? null : identifier(value, field);
    }

    /**
     * 空集合表示没有声明授权范围；null 和非法元素均拒绝。
     */
    public static Set<String> identifiers(Set<String> values, String field) {
        required(values, field);
        values.forEach(value -> identifier(value, field + " element"));
        return Set.copyOf(values);
    }

    /**
     * 查询地址接受站内绝对路径或 HTTP(S) URL，不允许嵌入凭据、查询串或片段。
     */
    public static URI queryLocation(URI value, String field) {
        required(value, field);
        boolean validAbsolute = value.isAbsolute()
                && ("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                && value.getHost() != null;
        boolean validRelative = !value.isAbsolute() && value.getRawAuthority() == null
                && value.getPath() != null && value.getPath().startsWith("/");
        if ((!validAbsolute && !validRelative) || value.getRawUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException(field + " must be an HTTP(S) URL or local absolute path without credentials, query or fragment");
        }
        return value;
    }
}
