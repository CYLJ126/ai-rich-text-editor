package com.arte.ainew.common.validation;

import com.arte.core.pojo.value.StructuredValue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * AI 契约的场景上限及兼容校验入口，基础校验委托给 core。
 * 保留已有方法、返回值、异常及限额，避免迁移改变受理和快照校验语义。
 */
public final class ContractChecks {
    public static final int MAX_TEXT_CHARS = StructuredValue.MAX_TEXT_CHARS;
    public static final int MAX_ITEMS = StructuredValue.MAX_ITEMS;
    public static final int MAX_PARTS = 64;
    public static final int MAX_TOOLS = 128;

    private ContractChecks() {
    }

    public static String text(String value, String field, int limit) {
        return com.arte.core.utils.validation.ContractChecks.text(value, field, limit);
    }

    public static String id(String value, String field) {
        return com.arte.core.utils.validation.ContractChecks.id(value, field);
    }

    public static String optionalId(String value, String field) {
        return com.arte.core.utils.validation.ContractChecks.optionalId(value, field);
    }

    public static String digest(String value, String field) {
        return com.arte.core.utils.validation.ContractChecks.digest(value, field);
    }

    public static long range(long value, String field, long min, long max) {
        return com.arte.core.utils.validation.ContractChecks.range(value, field, min, max);
    }

    public static <T> List<T> list(Collection<T> values, String field, int min, int max) {
        return com.arte.core.utils.validation.ContractChecks.list(values, field, min, max);
    }

    public static <T> Set<T> set(Collection<T> values, String field, int max) {
        return com.arte.core.utils.validation.ContractChecks.set(values, field, max);
    }

    public static void unique(Collection<?> values, String field) {
        com.arte.core.utils.validation.ContractChecks.unique(values, field);
    }

    public static void ordered(Instant first, Instant second, String field) {
        com.arte.core.utils.validation.ContractChecks.ordered(first, second, field);
    }

    public static void require(boolean condition, String message) {
        com.arte.core.utils.validation.ContractChecks.require(condition, message);
    }
}
