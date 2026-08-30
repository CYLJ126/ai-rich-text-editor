package com.arte.core.cache;

import com.arte.core.cache.redis.RedissonCacheManager;
import com.arte.core.factory.YamlPropertySourceFactory;
import com.arte.core.serialize.SerializerFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * 如果想添加缓存层，在配置类中引入此类即可
 *
 * @author zhangsc
 * @since 2026/3/5 10:59
 */
@Slf4j
@Configuration
@PropertySource(value = "classpath:application-core.yml", factory = YamlPropertySourceFactory.class)
public class CacheAutoConfiguration {

    @Value("${cache.cache-type}")
    private String cacheType;

    @Value("${cache.default.cache-key-prefix:myCache:}")
    private String keyPrefix;

    @Value("${cache.default.expire:60000}")
    private Long expire;

    @Value("${cache.default.max-size:65536}")
    private Integer maxSize;

    @Bean
    public CacheKeyGenerator cacheKeyGenerator() {
        return new CacheKeyGenerator();
    }

    @Bean
    public SerializerFactory cacheSerializerFactory() {
        return new SerializerFactory();
    }

    @ConditionalOnMissingBean(CacheConfig.class)
    @Bean
    public CacheConfig defaultCacheConfig() {
        CacheConfig config = new CacheConfig();
        config.setDefaultExpire(expire);
        config.setMaxSize(maxSize);
        config.setKeyPrefix(keyPrefix);
        return config;
    }

    @ConditionalOnExpression("'${cache.cache-type:}'.matches('(?i)redis|redisson')")
    @Bean
    public CacheManager redissonCacheManager(RedissonClient redissonClient) {
        return new RedissonCacheManager(redissonClient, cacheKeyGenerator(), defaultCacheConfig());
    }

    @PostConstruct
    public void init() {
        // bean 声明会在 init() 后调用
        log.info("开始加载缓存配置，缓存类型：{}", cacheType);
    }
}
