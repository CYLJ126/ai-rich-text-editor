package com.arte.ainew.config;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.management.ToolManagementService;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.spi.persistence.ToolBindingStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolDiscoveryManagementStore;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 已有内部能力的管理用例装配；不触发 SQL、发现、发布或执行，HTTP 必须另外具备宿主授权端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
@AutoConfiguration(after = NewAiToolConfiguration.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class NewAiToolManagementConfiguration {

    @Bean
    ToolManagementService toolManagementService(ToolCatalogStore catalog, ToolReleaseStore releases, ToolBindingStore bindings,
                                                ToolReleaseService releaseService, ToolBindingService bindingService, LocalToolPublicationValidator validator,
                                                LocalToolDiscovery discovery, ToolDiscoveryManagementStore discoveryStore) {
        return new ToolManagementService(catalog, releases, bindings, releaseService, bindingService, validator, discovery, discoveryStore);
    }
}
