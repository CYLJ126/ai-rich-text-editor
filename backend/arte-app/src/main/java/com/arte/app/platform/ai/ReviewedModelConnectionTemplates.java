package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.spi.model.ModelConnectionTemplates;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;
import com.arte.core.spi.workspace.PolicyStore;

import java.util.List;
import java.util.function.Supplier;

/**
 * 从当前 FIXED／DATABASE 受信配置来源筛选个人模型可选择的连接模板。
 * <p>
 * 模板须已启用，采用受支持的 Chat Completions 协议及地址形式，并同时满足租户、空间的个人凭据、协议、供应商和目的地主机白名单。
 * 另外复核部署级 HTTP origin 白名单；空政策名单不放行，必要政策缺失按权威不可用处理。
 * <p>
 * 模板沿用审核后的协议、目标地址和连接限制，但移除平台 SecretRef，防止用户借选择模板使用平台密钥。
 * 读取当前政策依赖调用方的原事务及范围治理门控；这里只筛选连接条件，不代替操作者或模型资源授权。
 * 内容摘要用于识别引用不变时模板属性的变化，不构成使用许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class ReviewedModelConnectionTemplates implements ModelConnectionTemplates {

    private final Supplier<NewAiProperties> source;
    private final PolicyStore policies;
    private final HttpEgressPolicy egress;

    public ReviewedModelConnectionTemplates(Supplier<NewAiProperties> source, PolicyStore policies, HttpEgressPolicy egress) {
        this.source = source;
        this.policies = policies;
        this.egress = egress;
    }

    private boolean allowed(PolicyRules r, ConnectionDefinition c) {
        return r.connectionRestrictions(c.protocol().id(), c.providerId(), c.endpoint().getHost()).isEmpty();
    }

    @Override
    public List<ConnectionDefinition> available(Scope scope) {
        TenantPolicy tenant = policies.findCurrentTenantPolicy(scope.tenantId()).orElseThrow(ReviewedModelConnectionTemplates::unavailable);
        WorkspacePolicy workspace = policies.findCurrentWorkspacePolicy(scope).orElseThrow(ReviewedModelConnectionTemplates::unavailable);
        var origins = egress.allowedOrigins();
        return source.get().connections().stream()
                .filter(c -> c.state() == ConnectionDefinition.State.ENABLED
                        && c.protocol().equals(ChatCompletionsSseProtocolAdapter.DEFINITION)
                        && allowed(tenant.rules(), c) && allowed(workspace.rules(), c))
                .filter(connection -> HttpEgressPolicy.allows(origins, connection.endpoint()))
                .map(connectionDefinition -> new ConnectionDefinition(connectionDefinition.definition(),
                        connectionDefinition.providerId(), connectionDefinition.protocol(), connectionDefinition.endpoint(),
                        null, connectionDefinition.state(), connectionDefinition.connectTimeout(),
                        connectionDefinition.responseTimeout(), connectionDefinition.maxResponseBytes()))
                .toList();
    }

    @Override
    public ConnectionDefinition require(Scope scope, DefinitionRef ref) {
        return available(scope).stream().filter(c -> c.definition().equals(ref)).findFirst()
                .orElseThrow(() -> new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "model-template"));
    }

    @Override
    public String digest(ConnectionDefinition connection) {
        return DefaultCommandExecutor.digest(List.of(connection.definition().id(), connection.definition().version(),
                connection.providerId(), connection.protocol().id(), connection.protocol().version(),
                connection.endpoint().toASCIIString(), connection.state().name(), connection.connectTimeout().toString(),
                connection.responseTimeout().toString(), Integer.toString(connection.maxResponseBytes())));
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "model-template");
    }
}
