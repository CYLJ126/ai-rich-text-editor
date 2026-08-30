package com.nip.app.web.aspect;

import com.nip.core.datasource.dynamic.DbContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AOP 实现的数据源切换，加了{@link org.springframework.transaction.annotation.Transactional} 是不可以切的
 *
 * @author zhangsc ≧◔◡◔≦
 * @version 1.0.0 ✵
 * @since 2021/2/6 16:17 ✾
 **/
@Component
@Order(value = -100)
@Slf4j
@Aspect
public class DataSourceSwitchAspect {
    @Pointcut("execution(* com.nip.app.mapper..*.*(..))")
    private void appAspect() {
    }

    @Pointcut("execution(* com.nip.chlorophyll.dao..*.*(..))")
    private void chlorophyllAspect() {
    }

    @Before("appAspect()")
    public void app() {
        log.info("切换到 app 数据源...");
        DbContextHolder.setDataSourceKey("app");
    }

    @Before("chlorophyllAspect()")
    public void chlorophyll() {
        log.info("切换到 chlorophyll 数据源...");
        DbContextHolder.setDataSourceKey("chlorophyll");
    }

    @After("appAspect() || chlorophyllAspect()")
    public void clear() {
        log.info("清除数据源关联-{}", DbContextHolder.getDataSourceKey());
        DbContextHolder.clearDataSourceKey();
    }
}
