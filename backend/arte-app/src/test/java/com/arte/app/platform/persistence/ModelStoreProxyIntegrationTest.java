package com.arte.app.platform.persistence;

import com.arte.app.platform.persistence.mapper.PlatformModelDraftMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelPublicationMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.service.platform.ModelDraftStoreServiceImpl;
import com.arte.app.service.platform.ModelPublicationStoreServiceImpl;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证正式存储类在事务代理开启时可以装配；直接 new 或未启用事务的测试不能覆盖此启动路径。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 125:35 ✾
 */
class ModelStoreProxyIntegrationTest {

    @Test
    void modelStoresSupportClassBasedTransactionProxies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(PlatformModelResourceMapper.class, () -> mock(PlatformModelResourceMapper.class));
            context.registerBean(ModelDraftStoreServiceImpl.class, () -> new ModelDraftStoreServiceImpl(
                    context.getBean(PlatformModelResourceMapper.class), mock(PlatformModelDraftMapper.class),
                    mock(PublicTransactionRunner.class), new PlatformJson()));
            context.registerBean(ModelPublicationStoreServiceImpl.class, () -> new ModelPublicationStoreServiceImpl(
                    context.getBean(PlatformModelResourceMapper.class), mock(PlatformModelPublicationMapper.class),
                    mock(PublicTransactionRunner.class), new PlatformJson()));

            context.refresh();

            // ServiceImpl 继承的事务方法会让两个 Bean 都进入 CGLIB 代理路径。
            assertTrue(AopUtils.isCglibProxy(context.getBean(ModelDraftStoreServiceImpl.class)));
            assertTrue(AopUtils.isCglibProxy(context.getBean(ModelPublicationStoreServiceImpl.class)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
