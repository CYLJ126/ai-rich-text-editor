package com.arte.ainew.config;

import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.approval.ToolApprovalRecovery;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.persistence.mybatis.*;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * 当前宿主明确提供受理／执行授权后才接通内部 TOOL 受理和派发；不注册 HTTP，不自动发现或创建默认绑定。
 * 与通用执行共用持久化及 Worker；三个开关共同开启，缺少真正执行依赖时失败，不提供默认放行或空派发器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:00 ✾
 */
@AutoConfiguration(after = {NewAiAdmissionConfiguration.class, NewAiToolGatewayConfiguration.class})
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled"}, havingValue = "true")
@ConditionalOnBean({ToolAdmissionAccess.class, ToolExecutionAccess.class})
@EnableConfigurationProperties({NewAiExecutionProperties.class, NewAiToolApprovalProperties.class})
public class NewAiToolExecutionConfiguration {

    /**
     * 与通用 Invocation 共用精确的物理数据源、有界 JDBC 调度器及事务内同步提交方法。
     */
    @Bean("newAiToolAdmissionStore")
    MybatisToolAdmissionStore toolAdmissionStore(NewAiProperties properties, ListableBeanFactory beans, ExecutionRecordCodec codec,
                                                 @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                 @Qualifier("newAiClock") Clock clock, MybatisExecutionPersistence executions,
                                                 MybatisToolBindingStore bindings, MybatisToolCatalogStore catalog) {
        return new MybatisToolAdmissionStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, executions, bindings, catalog, clock);
    }

    /**
     * 内部事务能力；公开审批入口及其宿主授权在 S3-02 接入，本批不注册 HTTP 或默认审批人。
     */
    @Bean("newAiToolApprovalStore")
    MybatisToolApprovalStore toolApprovalStore(NewAiProperties properties, ListableBeanFactory beans, ExecutionRecordCodec codec,
                                               @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                               MybatisExecutionPersistence executions, MybatisToolBindingStore bindings) {
        return new MybatisToolApprovalStore(beans.getBean(properties.dataSourceBean(), DataSource.class), codec, scheduler, executions, bindings);
    }

    @Bean("newAiToolApprovalRecovery")
    @ConditionalOnProperty(prefix = "arte", name = {"ai-new-tool-approval.enabled", "ai-new-execution.worker-enabled"}, havingValue = "true")
    ToolApprovalRecovery toolApprovalRecovery(MybatisToolApprovalStore store, NewAiToolApprovalProperties properties,
                                              @Qualifier("newAiPersistenceScheduler") Scheduler scheduler) {
        return new ToolApprovalRecovery(store, properties, scheduler);
    }

    /**
     * 受理用例由可信宿主权限端口门控；公开 HTTP 只提交意图并确认预览，不注入默认授权。
     */
    @Bean("newAiToolAdmissionService")
    ToolAdmissionService toolAdmissionService(ToolBindingService bindings, MybatisToolCatalogStore catalog,
                                              MybatisToolAdmissionStore store, ToolAdmissionAccess access, NewAiProperties properties,
                                              @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock) {
        var limits = properties.limits();
        var policy = new ToolExecutionPolicy(limits.maximumTimeout(), 10, Duration.ZERO, limits.maxInputBytes(), limits.maxOutputBytes(),
                ToolExecutionPolicy.ApprovalMode.NONE);
        return new ToolAdmissionService(bindings, catalog, store, access, new ToolSchemaValidator(), policy, scheduler, clock);
    }

    /**
     * 构造不访问数据库或业务工具；通用协调器在读取权威能力后路由到本派发器。
     */
    @Bean("newAiToolDispatcher")
    ToolDispatcher toolDispatcher(MybatisExecutionPersistence executions, MybatisToolAdmissionStore snapshots,
                                  ToolBindingService bindings, ToolAdmissionAccess access, ToolGateway gateway,
                                  MybatisPayloadPersistence results, ExecutionRecordCodec codec, NewAiExecutionProperties properties,
                                  @Qualifier("newAiToolDiscoveryScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock) {
        return new ToolDispatcher(executions, executions, executions, snapshots, bindings, access, gateway, results, codec, scheduler, clock,
                properties.attemptLease());
    }
}
