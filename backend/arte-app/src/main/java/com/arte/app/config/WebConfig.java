package com.arte.app.config;

import com.arte.app.config.bean.LoginProperties;
import com.arte.app.config.bean.QiniuProperties;
import com.arte.app.config.bean.RichTextStorageProperties;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.web.interceptor.ValidateInterceptor;
import com.arte.core.serialize.SerializerFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJackson3Codec;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Web 配置
 *
 * @author zhangsc
 * @since 2024/7/12 10:57
 */
@Slf4j
@Configuration
@ComponentScan("com.arte.core")
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private ValidateInterceptor validateInterceptor;

    @Bean
    @ConfigurationProperties(prefix = "login")
    public LoginProperties loginProperties() {
        return new LoginProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "jwt")
    public WebSecurityProperties securityProperties() {
        return new WebSecurityProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "rich-text.storage.qiniu")
    public QiniuProperties qiniuProperties() {
        return new QiniuProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "rich-text.storage")
    public RichTextStorageProperties richTextStorageProperties() {
        return new RichTextStorageProperties();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 密码加密方式
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JsonMapper objectMapper() {
        return SerializerFactory.buildJsonMapperWithoutTypeProperty();
    }

    @Bean
    public RedissonClient redissonClient() throws IOException {
        Config config = Config.fromYAML(WebConfig.class.getResource("/redisson.yml"));
        JsonJackson3Codec codec = new JsonJackson3Codec(SerializerFactory.buildJsonMapperWithTypeProperty());
        config.setCodec(codec);
        return Redisson.create(config);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 权限校验 目前只为adminController服务
        registry.addInterceptor(validateInterceptor)
                .addPathPatterns("/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        RichTextStorageProperties.Local local = richTextStorageProperties().getLocal();
        registry.addResourceHandler("/richText/file/local/image/**")
                .addResourceLocations("file:" + ensureTrailingSlash(local.getImagePath()));
        registry.addResourceHandler("/richText/file/local/file/**")
                .addResourceLocations("file:" + ensureTrailingSlash(local.getFilePath()));
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        log.info("添加自定义 HttpMessageConverter");
        builder.addCustomConverter(new StringHttpMessageConverter(StandardCharsets.UTF_8));
        builder.addCustomConverter(new ByteArrayHttpMessageConverter());
        builder.addCustomConverter(new ResourceHttpMessageConverter());
        builder.addCustomConverter(new FormHttpMessageConverter());
        // Jackson3 放最后做兜底 JSON 序列化
        builder.addCustomConverter(new JacksonJsonHttpMessageConverter(objectMapper()));
    }

    private String ensureTrailingSlash(String path) {
        String normalized = path == null || path.isBlank()
                ? "./data/uploads/richtext"
                : path.trim();
        normalized = normalized.replace("\\", "/");
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }
}
