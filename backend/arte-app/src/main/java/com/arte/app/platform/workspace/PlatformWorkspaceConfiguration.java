package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.platform.persistence.WorkspaceCursorStore;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.platform.CommandReceiptQueryService;
import com.arte.core.api.workspace.*;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 公共应用服务装配
 * <p>
 * 身份和数据库为显式前置条件，不注册 HTTP、不执行 DDL 或启动回填。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:22 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformWorkspaceProperties.class)
public class PlatformWorkspaceConfiguration {

    public PlatformWorkspaceConfiguration(Environment environment) {
        if (!environment.getProperty("arte.platform.identity.enabled", Boolean.class, false)
                || !environment.getProperty("arte.platform.persistence.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Workspace services require arte.platform.identity.enabled=true and arte.platform.persistence.enabled=true");
        }
    }

    /**
     * 提供空间服务使用的 UTC 系统时钟，用于业务时间、回执及变更事件的时间记录。
     * 该时钟通过限定名称注入，不作为其他 Clock 依赖的默认候选；目录浏览的耗时预算另用单调时钟计量。
     *
     * @return 空间服务专用的 UTC 系统时钟
     */
    @Bean(defaultCandidate = false)
    Clock platformWorkspaceClock() {
        return Clock.systemUTC();
    }

    /**
     * 装配租户和空间的初始政策工厂。
     * 新建个人租户时读取当前数据库模板，新建空间时继承所属租户政策；模板不在启动时缓存。
     *
     * @param templates 版本化个人租户政策模板存储
     * @return 初始政策工厂
     */
    @Bean
    DefaultWorkspacePolicies platformWorkspacePolicies(PersonalTenantPolicyTemplateStore templates) {
        return new DefaultWorkspacePolicies(templates);
    }

    /**
     * 组合身份、持久化和事务依赖，构建共享授权检查、命令执行及变更副作用的核心空间服务集合。
     * 目录浏览预算来自配置，耗时由 System.nanoTime 计量；各公共接口 Bean 复用此集合中的服务实例。
     *
     * @param identity         当前主体身份及状态的可信提供者
     * @param transactions     平台本地事务执行器，供命令执行和事务内门控使用
     * @param tenants          租户元数据存储
     * @param workspaces       空间元数据及成员候选扫描存储
     * @param tenantMembers    租户成员资格存储
     * @param workspaceMembers 空间成员资格存储
     * @param policies         租户及空间政策存储
     * @param preferences      主体空间偏好存储
     * @param receipts         幂等命令回执存储
     * @param outbox           公共变更事件的事务发件箱存储
     * @param audit            审计记录写入接口
     * @param ids              平台标识生成器
     * @param clock            空间服务专用的业务时钟
     * @param defaults         初始租户和空间政策工厂
     * @param facts            空间授权事实的批量读取存储
     * @param provisioning     空间创建事务内的资源初始化参与者集合
     * @param properties       空间功能配置，提供目录浏览扫描预算
     * @return 已组合全部依赖的核心空间服务集合
     */
    @Bean
    DefaultWorkspaceServices platformWorkspaceServices(IdentityProvider identity,
                                                       @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                                       TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                                       WorkspaceMembershipStore workspaceMembers, PolicyStore policies, PreferenceStore preferences,
                                                       CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                                       @Qualifier("platformWorkspaceClock") Clock clock,
                                                       @Qualifier("platformWorkspacePolicies") DefaultWorkspacePolicies defaults,
                                                       WorkspaceAccessFactsStore facts, java.util.List<WorkspaceProvisioningParticipant> provisioning,
                                                       PlatformWorkspaceProperties properties) {
        return new DefaultWorkspaceServices(identity, transactions, tenants, workspaces, tenantMembers,
                workspaceMembers, policies, preferences, receipts, outbox, audit, ids, clock, defaults, facts, provisioning,
                properties.directory().budget(), System::nanoTime);
    }

    /**
     * 暴露个人空间初始化服务，为当前主体创建或取得唯一个人租户及默认空间。
     *
     * @param services 核心空间服务集合
     * @return 集合中的个人空间服务实例
     */
    @Bean
    PersonalWorkspaceService personalWorkspaceService(DefaultWorkspaceServices services) {
        return services.personal();
    }

    /**
     * 暴露空间目录和基础管理服务，支持可访问空间浏览、统计、查询、创建及改名。
     *
     * @param services 核心空间服务集合
     * @return 集合中的空间公共服务实例
     */
    @Bean
    WorkspaceService workspaceService(DefaultWorkspaceServices services) {
        return services.workspaces();
    }

    /**
     * 暴露当前主体的空间偏好服务，负责启动空间、最近访问空间和收藏的读取与更新。
     *
     * @param services 核心空间服务集合
     * @return 集合中的空间偏好服务实例
     */
    @Bean
    WorkspacePreferenceService workspacePreferenceService(DefaultWorkspaceServices services) {
        return services.preferences();
    }

    /**
     * 暴露租户和空间政策服务，按当前访问资格读取政策，并以版本校验及命令回执执行政策更新。
     *
     * @param services 核心空间服务集合
     * @return 集合中的政策服务实例
     */
    @Bean
    WorkspacePolicyService workspacePolicyService(DefaultWorkspaceServices services) {
        return services.policies();
    }

    /**
     * 暴露空间范围验证服务，核验当前主体在所选租户及空间的成员资格和政策。
     * 返回的范围为验证时的快照，最终写入仍需事务内门控。
     *
     * @param services 核心空间服务集合
     * @return 集合中的空间范围验证服务实例
     */
    @Bean
    ScopeResolver workspaceScopeResolver(DefaultWorkspaceServices services) {
        return services.scopes();
    }

    /**
     * 暴露平台授权服务，组合当前身份、成员资格、政策和资源事实进行访问判断。
     * 响应式适配器通过限定名称注入该服务。
     *
     * @param services 核心空间服务集合
     * @return 集合中的平台授权服务实例
     */
    @Bean
    AuthorizationService platformAuthorizationService(DefaultWorkspaceServices services) {
        return services.authorization();
    }

    /**
     * 暴露空间写入的最终事务门控，按约定顺序取得治理锁，并以当前读取重新验证权限后执行写入。
     * 门控必须在调用方实际事务内使用，以便授权检查与业务提交处于同一事务。
     *
     * @param services 核心空间服务集合
     * @return 集合中的空间写入门控实例
     */
    @Bean
    WorkspaceWriteGuard workspaceWriteGuard(DefaultWorkspaceServices services) {
        return services.writeGuard();
    }

    /**
     * 暴露命令回执查询服务，仅允许原操作者读取当前仍有资格查看的历史结果。
     * 查询会重新检查结果可见性，不重新执行原命令。
     *
     * @param services 核心空间服务集合
     * @return 集合中的命令回执查询服务实例
     */
    @Bean
    CommandReceiptQueryService workspaceCommandReceiptQueryService(DefaultWorkspaceServices services) {
        return services.receiptQueries();
    }

    /**
     * 装配空间目录浏览适配器，将核心扫描边界转换为绑定主体及查询条件的不透明游标。
     * 每次继续浏览均重新调用核心授权检查，游标仅保存导航状态。
     *
     * @param workspaces 负责预算扫描和当前访问资格验证的空间服务
     * @param cursors    保存有有效期、可跨实例读取的公开游标状态的存储
     * @return 面向客户端的空间目录浏览适配器
     */
    @Bean
    WorkspaceDirectoryBrowser workspaceDirectoryBrowser(WorkspaceService workspaces, WorkspaceCursorStore cursors) {
        return new WorkspaceDirectoryBrowser(workspaces, cursors);
    }

    /**
     * 装配可信会话到空间公共服务的响应式适配器，供 HTTP 和其他响应式调用方使用。
     * 调用时复制最小会话身份，订阅后在有界数据库线程核验当前身份并执行完整同步调用。
     *
     * @param identities 将认证会话映射为可信主体并核验当前身份的 RBAC 身份提供者
     * @param executor 将同步持久化调用及事务调度到数据库工作线程的执行器
     * @param personal 个人租户及默认空间初始化服务
     * @param workspaces 空间目录和基础管理服务
     * @param preferences 当前主体的空间偏好服务
     * @param scopes 空间范围验证服务
     * @param authorization 平台授权服务
     * @param guard 实际事务内的空间写入最终门控
     * @param receipts 命令回执查询服务
     * @param policies 租户及空间政策服务
     * @param browser 公开游标与核心目录浏览之间的适配器
     * @return 空间公共服务的响应式调用入口
     */
    @Bean
    ReactiveWorkspaceOperations reactiveWorkspaceOperations(RbacIdentityProvider identities,
                                                            PlatformPersistenceExecutor executor, PersonalWorkspaceService personal, WorkspaceService workspaces,
                                                            WorkspacePreferenceService preferences, ScopeResolver scopes,
                                                            @Qualifier("platformAuthorizationService") AuthorizationService authorization, WorkspaceWriteGuard guard,
                                                            CommandReceiptQueryService receipts, WorkspacePolicyService policies,
                                                            WorkspaceDirectoryBrowser browser) {
        return new ReactiveWorkspaceOperations(identities, executor, personal, workspaces, preferences, scopes, authorization, guard, receipts, policies, browser);
    }
}
