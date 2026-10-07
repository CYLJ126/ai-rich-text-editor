package com.arte.core.serialize;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间格式的反序列化器
 * <p>
 * 查询范围边界只接受带偏移的 ISO 日期时间字符串，避免数字时间戳的秒／毫秒歧义。
 * 通过字段注解使用，不改变其他 Instant 字段的映射规则；JSON null 沿用 Jackson 默认处理。
 * <p>
 * {@code yyyy-MM-dd} 与 {@code uuuu-MM-dd} 的区别，适用 {@link DateTimeFormatter} 的格式规则：
 * | 格式    | 含义                            | 公元 2026 年 | 公元前 1 年 |
 * |---     |---                             |---          |---         |
 * | `uuuu` | 连续年份，支持 0 和负数           | `2026`      | `0000`     |
 * | `yyyy` | 纪元内年份，需结合 `G` 区分公元前后 | `2026`     | `0001`     |
 * <p>
 * <ul>
 *   <li>{@code yyyy} 表示纪元内年份，严格解析时缺少纪元信息可能无法构造 {@code LocalDate}；</li>
 *   <li>{@code uuuu} 直接提供完整年份，适合配合 {@code lenient = FALSE} 校验非法日期。</li>
 * </ul>
 * <p>参数要求使用 {@code uuuu-MM-dd} 以严格反序列化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
public final class InstantStringDeserializer extends ValueDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(Instant.class,
                    "时间点必须是带时区偏移的 ISO-8601 字符串，不能使用数字时间戳");
        }
        try {
            // ISO_OFFSET_DATE_TIME 严格校验日历日期和时间，转换时保留纳秒精度。
            return OffsetDateTime.parse(parser.getString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException exception) {
            return context.reportInputMismatch(Instant.class,
                    "时间点必须是有效且带时区偏移的 ISO-8601 字符串，例如 2026-10-07T00:00:00+08:00");
        }
    }
}
