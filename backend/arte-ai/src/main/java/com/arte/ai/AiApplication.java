package com.arte.ai;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;

/**
 * @author zhangsc
 * @since 2025/5/29 20:59
 */
@SpringBootApplication
@PropertySource("classpath:application-ai.yml")
public class AiApplication {
    public static void main(String[] args) {
        System.out.println("Hello World!");
    }
}
