package com.arte.ai.service.tool.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 查找包含 Spring AI {@link Tool} 方法的本地单例 Bean
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiToolObjectLocator {

    private final ConfigurableListableBeanFactory beanFactory;

    public List<Object> findToolObjects() {
        List<Object> result = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (!beanFactory.isSingleton(beanName)) {
                continue;
            }
            try {
                Class<?> beanType = beanFactory.getType(beanName, false);
                if (beanType != null && hasToolMethod(beanType)) {
                    result.add(beanFactory.getBean(beanName));
                }
            } catch (BeansException | LinkageError exception) {
                log.debug("Skip local tool candidate bean {}: {}", beanName, exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private boolean hasToolMethod(Class<?> beanType) {
        Class<?> userClass = ClassUtils.getUserClass(beanType);
        return Arrays.stream(ReflectionUtils.getAllDeclaredMethods(userClass))
                .anyMatch(method -> Modifier.isPublic(method.getModifiers())
                        && method.isAnnotationPresent(Tool.class));
    }
}
