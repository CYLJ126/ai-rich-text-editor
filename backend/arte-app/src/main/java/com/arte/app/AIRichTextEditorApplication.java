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
// 新 AI 服务由自动配置装配；HTTP 入口位于独立的 ainew.web 包，需要显式扫描。
@ComponentScan(basePackages = {"com.arte.core", "com.arte.app", "com.arte.ai", "com.arte.ainew.web"})
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
