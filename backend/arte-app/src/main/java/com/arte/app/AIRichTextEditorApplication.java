package com.arte.app;

import com.arte.core.cache.CacheAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * App 启动类
 *
 * @author zhangsc
 */
@EnableScheduling
@SpringBootApplication
@Import(CacheAutoConfiguration.class)
// 这里指定了扫描路径，会抑制模块中的自动配置上的自动扫描，所以要显示指定扫描路径
@ComponentScan(basePackages = {"com.arte.core", "com.arte.app", "com.arte.ai"})
public class AIRichTextEditorApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(AIRichTextEditorApplication.class, args);
        // 打印所有 WebMvcConfigurer 的实现
        Map<String, WebMvcConfigurer> configurers = ctx.getBeansOfType(WebMvcConfigurer.class);
        configurers.forEach((name, bean) ->
                System.out.println("WebMvcConfigurer Bean 打印: " + name + " -> " + bean.getClass().getName())
        );
    }
}
