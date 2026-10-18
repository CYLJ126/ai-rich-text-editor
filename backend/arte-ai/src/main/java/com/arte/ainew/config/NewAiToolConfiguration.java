package com.arte.ainew.config;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 显式启用本地 TOOL 发现及内部生命周期治理；组合模块必须提供 LocalToolRegistry，不搜集任意来源／执行器 Bean。
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

    /**
     * 本地发布／重新启用预检；沿用有界元数据线程及读取期限，不试调用工具。
     */
    @Bean("newAiToolPublicationValidator")
    LocalToolPublicationValidator publicationValidator(LocalToolDiscovery discovery, LocalToolRegistry registry,
                                                       @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler,
                                                       @Qualifier("newAiClock") Clock clock, NewAiToolProperties properties) {
        return new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock,
                properties.discovery().sourceTimeout());
    }

    /**
     * 内部治理服务；不自动发布，不注册管理 HTTP，当前授权接线由 S1-08 完成。
     */
    @Bean("newAiToolReleaseService")
    ToolReleaseService toolReleaseService(ToolReleaseStore store, LocalToolPublicationValidator validator) {
        return new ToolReleaseService(store, validator);
    }
}
