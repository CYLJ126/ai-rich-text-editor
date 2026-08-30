package com.arte.core.cache;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.*;

@Slf4j
public abstract class AbstractCacheManager implements CacheManager, ApplicationContextAware {

    @Setter
    @Getter
    private ApplicationContext applicationContext;

    @Value("${cache.scan-packages}")
    private String basePackages;

    @Override
    public void registerCache(Collection<CacheTypeRecord> cacheTypeRecords) {
        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        for (CacheTypeRecord record : cacheTypeRecords) {
            String cacheName = record.cacheType().getCacheName();
            Class<?> keyType = record.cacheKeyType();
            Class<?> valueType = record.cacheValueType();
            // 获取或创建缓存实例
            Cache<?, ?> cache = getCache(cacheName, keyType, valueType);
            // 检查是否已存在同名单例 Bean
            if (beanFactory.containsSingleton(cacheName)) {
                log.info("缓存 Bean {} 已存在，跳过注册", cacheName);
                continue;
            }
            // 注册单例实例
            beanFactory.registerSingleton(cacheName, cache);
        }
    }

    @Override
    public void registerCachesFromAnnotatedClasses(Collection<Class<?>> annotatedClasses) {
        List<CacheTypeRecord> records = new ArrayList<>();
        for (Class<?> clazz : annotatedClasses) {
            CacheTypeInfo annotation = clazz.getAnnotation(CacheTypeInfo.class);
            if (annotation == null) {
                log.warn("类 {} 未标注 @CacheTypeInfo 注解，跳过", clazz.getName());
                continue;
            }
            CacheTypeEnum cacheType = annotation.cacheType();
            Class<?> keyType = annotation.cacheKeyType();
            Class<?> valueType = annotation.cacheValueType();
            records.add(new CacheTypeRecord(cacheType, keyType, valueType));
        }
        registerCache(records);
    }

    @Override
    public void registerCachesFromAnnotationScan(String basePackages) {
        String[] packages = basePackages.split(",");
        Set<Class<?>> classes = new HashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CacheTypeInfo.class));
        for (String pkg : packages) {
            String trimmedPkg = pkg.trim();
            if (trimmedPkg.isEmpty()) {
                continue;
            }
            Set<BeanDefinition> beanDefinitions = scanner.findCandidateComponents(trimmedPkg);
            for (BeanDefinition bd : beanDefinitions) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    classes.add(clazz);
                } catch (ClassNotFoundException e) {
                    log.error("Class not found: {}", bd.getBeanClassName(), e);
                }
            }
        }
        registerCachesFromAnnotatedClasses(new ArrayList<>(classes));
    }

    @PostConstruct
    public void afterPropertiesSet() {
        if (StrUtil.isNotBlank(basePackages)) {
            registerCachesFromAnnotationScan(basePackages);
        }
    }
}
