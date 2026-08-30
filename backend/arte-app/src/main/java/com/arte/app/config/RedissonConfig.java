package com.arte.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * @author zhangsc
 */
@Configuration
@Slf4j
public class RedissonConfig {

//    @Bean
//    public RedissonAutoConfigurationCustomizer redissonCustomizer() {
//        return config -> {
//            // 自定义 ObjectMapper
//            ObjectMapper mapper = SerializerFactory.defaultMapper();
//            // 使用该 ObjectMapper 构造 JsonJackson3Codec 并设置到 Config
//            config.setCodec(new JsonJackson3Codec(mapper));
//        };
//    }
}
