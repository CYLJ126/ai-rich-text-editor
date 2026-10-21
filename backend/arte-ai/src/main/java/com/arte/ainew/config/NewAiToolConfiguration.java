package com.arte.ainew.config;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.discovery.ToolStartupDiscovery;
import com.arte.ainew.application.tool.release.StaticToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.spi.persistence.ToolBindingStore;
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
 * 显式启用静态 TOOL 发现及内部生命周期治理；组合模块必须提供 StaticToolRegistry，不搜集任意来源／执行器 Bean。
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
    @Bean("newAiStaticToolDiscovery")
    StaticToolDiscovery staticToolDiscovery(StaticToolRegistry registry, ToolCatalogStore store,
                                          @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler,
                                          @Qualifier("newAiClock") Clock clock, NewAiToolProperties properties) {
        return new StaticToolDiscovery(registry, store, new ToolSchemaValidator(), scheduler, clock,
                properties.discovery().sourceTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new-tool.discovery", name = "refresh-on-startup", havingValue = "true")
    ToolStartupDiscovery toolStartupDiscovery(StaticToolDiscovery discovery) {
        return new ToolStartupDiscovery(discovery);
    }

    /**
     * 静态工具发布／重新启用预检；沿用有界元数据线程及读取期限，不试调用工具。
     */
    @Bean("newAiToolPublicationValidator")
    StaticToolPublicationValidator publicationValidator(StaticToolDiscovery discovery, StaticToolRegistry registry,
                                                       @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler,
                                                       @Qualifier("newAiClock") Clock clock, NewAiToolProperties properties) {
        return new StaticToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock,
                properties.discovery().sourceTimeout());
    }

    /**
     * 内部治理服务；不自动发布，不注册管理 HTTP，当前授权接线由 S1-08 完成。
     */
    @Bean("newAiToolReleaseService")
    ToolReleaseService toolReleaseService(ToolReleaseStore store, StaticToolPublicationValidator validator) {
        return new ToolReleaseService(store, validator);
    }

    /**
     * 个人绑定内部管理／解析，不自动建绑、不接入聊天或 Worker。
     */
    @Bean("newAiToolBindingService")
    ToolBindingService toolBindingService(ToolBindingStore store, StaticToolDiscovery discovery, StaticToolRegistry registry,
                                          @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler, NewAiToolProperties properties) {
        return new ToolBindingService(store, discovery, registry, scheduler, properties.discovery().sourceTimeout());
    }
}
