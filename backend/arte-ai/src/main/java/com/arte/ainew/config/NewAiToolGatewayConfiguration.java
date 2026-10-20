package com.arte.ainew.config;

import com.arte.ainew.application.gateway.DefaultToolGateway;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;

/**
 * 宿主显式提供执行授权端口后才装配本地只读网关；只有管理能力的部署无需伪造允许执行的授权器。
 * 不开启 TOOL 受理或 Worker；后续执行装配必须要求真实 ToolGateway，不能以缺失授权静默放行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 15:21 ✾
 */
@AutoConfiguration(after = NewAiToolConfiguration.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
@ConditionalOnBean(ToolExecutionAccess.class)
public class NewAiToolGatewayConfiguration {

    /**
     * 隔离同步适配入口、Schema 和结果编码；最多四线程、每线程 32 个排队任务，容器关闭时释放。
     */
    @Bean(name = "newAiToolInvocationScheduler", destroyMethod = "dispose")
    Scheduler scheduler() {
        return Schedulers.newBoundedElastic(4, 32, "arte-ainew-tool-invoke");
    }

    /**
     * 构造零 SQL／零执行；注册网关不等于对外开放工具，调度容量和耐久受理仍由后续批次实现。
     */
    @Bean("newAiToolGateway")
    ToolGateway gateway(ToolBindingService bindings, ToolCatalogStore catalog, LocalToolRegistry registry,
                        ToolExecutionAccess access, ExecutionRecordCodec codec,
                        @Qualifier("newAiToolInvocationScheduler") Scheduler scheduler,
                        @Qualifier("newAiClock") Clock clock, ObjectProvider<ToolApprovalStore> approvals) {
        return new DefaultToolGateway(bindings, catalog, registry, access, new ToolSchemaValidator(), codec, scheduler, clock,
                call -> {
                    var store = approvals.getIfAvailable();
                    return store == null ? reactor.core.publisher.Mono.error(new org.springframework.security.access.AccessDeniedException("Approval store unavailable"))
                            : store.requireApproved(call);
                });
    }
}
