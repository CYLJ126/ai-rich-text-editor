package com.nip.app.web.controller.test;

import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.cache.Cache;
import com.nip.core.cache.CacheManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/25 22:15 ✾
 */
@Slf4j
@RestController
@RequestMapping("/test")
public class StartTestController {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private CacheManager cacheManager;

    @AnonymousAccess
    @GetMapping(value = "/start", produces = "application/json")
    @ResponseBody
    public Map<String, Object> start() {
        Map<String, Object> result = new HashMap<>();
        // 检查所有 ServletRegistrationBean
        String[] beanNames = applicationContext.getBeanNamesForType(ServletRegistrationBean.class);
        result.put("servletBeans", Arrays.asList(beanNames));
        // 检查 Druid StatViewServlet
        try {
            result.put("statViewServletExists", applicationContext.containsBean("druidStatViewServlet"));
        } catch (Exception e) {
            log.error("检查 Druid StatViewServlet 失败", e);
            result.put("statViewServletExists", false);
            result.put("statViewServletError", e.getMessage());
        }
        // 检查缓存管理器
        try {
            Cache<Object, Object> testCache = cacheManager.createCache("testCache");
            testCache.put("testAAA", new MyTestCacheDto("testAAA", "testValue", LocalDateTime.now()));
            result.put("testAAA", testCache.get("testAAA"));
            result.put("testCacheExists", true);
        } catch (Exception e) {
            log.error("检查缓存管理器失败", e);
            result.put("testCacheExists", false);
            result.put("testCacheError", e.getMessage());
        }
        return result;
    }

    public record MyTestCacheDto(String key, String value, LocalDateTime time) {
    }
}
