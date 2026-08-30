package com.arte.core.serialize;

import com.arte.core.cache.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonFactoryBuilder;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.util.JsonRecyclerPools;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.datatype.joda.JodaModule;

import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存序列化器工厂
 *
 * @author zhangsc
 * @since 2026/3/5 10:46
 */
@Slf4j
public class SerializerFactory {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, MySerializer<?>> serializerCache = new ConcurrentHashMap<>();

    public SerializerFactory() {
        this.objectMapper = buildJsonMapperWithoutTypeProperty();
    }

    @SuppressWarnings("unchecked")
    public <T> MySerializer<T> getSerializer(CacheConfig.SerializerType type, Class<T> targetType) {
        String key = type.name() + ":" + targetType.getName();

        return (MySerializer<T>) serializerCache.computeIfAbsent(key, k -> switch (type) {
            case JSON -> new JsonCacheSerializer<>(targetType, objectMapper);
            case JDK -> new JdkCacheSerializer<>(targetType);
            default -> {
                log.warn("不支持的序列化类型：{}，将使用默认序列化器：JSON", type);
                yield new JsonCacheSerializer<>(targetType, objectMapper);
            }
        });
    }

    public static JsonMapper buildJsonMapperWithTypeProperty() {
        return buildJsonMapper(true, "com.arte.", true, DefaultTyping.NON_FINAL_AND_RECORDS, "@class", "yyyy-MM-dd HH:mm:ss");
    }

    public static JsonMapper buildJsonMapperWithoutTypeProperty() {
        return buildJsonMapper(true, "com.arte.", false, DefaultTyping.JAVA_LANG_OBJECT, null, "yyyy-MM-dd HH:mm:ss");
    }

    /**
     * 构建 SSE 流式交互格式
     *
     * @return JsonMapper 实例
     */
    public static JsonMapper buildStreamJsonMapper() {
        JsonFactoryBuilder factoryBuilder = JsonFactory.builder();
        factoryBuilder.recyclerPool(JsonRecyclerPools.threadLocalPool());
        return JsonMapper.builder(factoryBuilder.build()).deactivateDefaultTyping()
                // 遇到未知属性时抛出异常，或在实体类上加 @JsonIgnoreProperties(ignoreUnknown = true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .addModule(new JodaModule())
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .enable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
                .defaultTimeZone(TimeZone.getDefault())
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .build();
    }

    /**
     * TODO <a href="https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring#spring-security-jackson-3-support">Spring Security 配置</a>
     * <p>
     * 构建 JsonMapper
     *
     * @param withThreadPool   是否开启线程池
     * @param packageName      允许的包名
     * @param withTypeProperty 是否开启默认类型，序列化时携带 @class 信息
     * @param timeFormat       时间格式化模式
     * @return JsonMapper 实例
     */
    public static JsonMapper buildJsonMapper(boolean withThreadPool,
                                             String packageName,
                                             boolean withTypeProperty,
                                             DefaultTyping defaultTyping,
                                             String classProperty,
                                             String timeFormat) {
        JsonFactoryBuilder factoryBuilder = JsonFactory.builder();
        if (withThreadPool) {
            factoryBuilder.recyclerPool(JsonRecyclerPools.threadLocalPool());
        }
        BasicPolymorphicTypeValidator polymorphicTypeValidator = BasicPolymorphicTypeValidator.builder()
                // 允许数组类型
                .allowIfSubTypeIsArray()
                // 允许特定包
                .allowIfSubType(packageName)
                // 允许 JDK 类型
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .build();
        JsonMapper.Builder builder = JsonMapper.builder(factoryBuilder.build());
        if (withTypeProperty) {
            // 开启默认类型，序列化时携带 @class 信息
            builder.activateDefaultTypingAsProperty(polymorphicTypeValidator, defaultTyping, classProperty);
        } else {
            builder.deactivateDefaultTyping();
        }
        return builder
                // 遇到未知属性时抛出异常，或在实体类上加 @JsonIgnoreProperties(ignoreUnknown = true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                // to use Joda date/time types
                .addModule(new JodaModule())
                // configure streaming JSON-escaping
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .enable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
                .defaultTimeZone(TimeZone.getDefault())
                .defaultDateFormat(new SimpleDateFormat(timeFormat))
                .build();
    }
}

