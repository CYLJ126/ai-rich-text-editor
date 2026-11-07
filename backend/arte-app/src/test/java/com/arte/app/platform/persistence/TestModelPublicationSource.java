package com.arte.app.platform.persistence;

import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.control.PublishedAiDefinitions;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.PublicTransactionRunner;

/**
 * 模型发布记录服务
 * <p>
 * 仅采纳已有审核发布；本批要求当前平台配置管理资格，不能凭创建私有模型认领平台连接。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public final class TestModelPublicationSource implements ModelPublicationSource {

    private final ControlConfiguration fixed;
    private final PublicTransactionRunner tx;
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;

    public TestModelPublicationSource(ControlConfiguration fixed, PublicTransactionRunner tx,
                                      RbacIdentityProvider identities, RbacToolPermissionReader permissions) {
        this.fixed = fixed;
        this.tx = tx;
        this.identities = identities;
        this.permissions = permissions;
    }

    @Override
    public ModelPublication requireCurrent(TrustedActor actor, Scope scope, ModelPublication.Source source, Long revision, DefinitionRef binding) {
        tx.requireWritable();
        if (source != ModelPublication.Source.FIXED) {
            throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
        }
        var name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.UNAUTHENTICATED));
        var current = permissions.current(name);
        if (!current.contains("admin") && !current.contains("aiConfiguration:manage"))
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        if (revision != null) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        ControlConfiguration properties = fixed;
        var definitions = new PublishedAiDefinitions(properties.releaseRef(), properties.capabilities(), properties.bindings(), properties.connections(),
                properties.rates().stream().map(r -> new RateDefinition(r.definition(), r.inputPerMillion(), r.outputPerMillion(), r.toolPerCall())).toList());
        var model = definitions.bindings().stream().filter(b -> b.definition().equals(binding)).findFirst().orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        return new ModelPublication(source, revision, definitions.releaseRef(), model,
                definitions.connections().stream().filter(c -> c.definition().equals(model.connection())).findFirst().orElseThrow(),
                definitions.rates().stream().filter(r -> r.definition().equals(model.rate())).findFirst().orElseThrow());
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-source");
    }
}
