package com.arte.ainew.config;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 显式启用本地 TOOL 发现；组合模块必须提供 LocalToolRegistry，不搜集任意来源／执行器 Bean。
 * 构造零 SQL、零发现、零执行，不接入 Worker，不代替后续管理 API 的当前授权及审计事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
@AutoConfiguration(after = NewAiAdmissionConfiguration.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
@EnableConfigurationProperties(NewAiToolProperties.class)
public class NewAiToolConfiguration {

    /**
     * 隔离本地元数据读取与 Schema 编译；最多两条线程、每线程 16 个排队任务，关闭时释放。
     */
    @Bean(name = "newAiToolDiscoveryScheduler", destroyMethod = "dispose")
    Scheduler scheduler() {
        return Schedulers.newBoundedElastic(2, 16, "arte-ainew-tool-discovery");
    }

    /**
     * 内部手动发现服务；开启时缺少显式注册表即装配失败，不注册空工具目录掩盖漏配。
     * 来源由组合模块构造，领域依赖继续通过构造器注入业务适配器。
     */
    @Bean("newAiLocalToolDiscovery")
    LocalToolDiscovery localToolDiscovery(LocalToolRegistry registry, ToolCatalogStore store,
                                          @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler,
                                          @Qualifier("newAiClock") Clock clock, NewAiToolProperties properties) {
        return new LocalToolDiscovery(registry, store, new ToolSchemaValidator(), scheduler, clock,
                properties.discovery().sourceTimeout());
    }
}
