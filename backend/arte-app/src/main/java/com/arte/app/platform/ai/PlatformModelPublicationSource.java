package com.arte.app.platform.ai;

import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.PublishedAiDefinitions;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
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
public final class PlatformModelPublicationSource implements ModelPublicationSource {

    private final NewAiProperties fixed;
    private final PlatformAiControlMapper mapper;
    private final PlatformJson json;
    private final PublicTransactionRunner tx;
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;

    public PlatformModelPublicationSource(NewAiProperties fixed, PlatformAiControlMapper mapper, PlatformJson json, PublicTransactionRunner tx,
                                          RbacIdentityProvider identities, RbacToolPermissionReader permissions) {
        this.fixed = fixed;
        this.mapper = mapper;
        this.json = json;
        this.tx = tx;
        this.identities = identities;
        this.permissions = permissions;
    }

    @Override
    public ModelPublication requireCurrent(TrustedActor actor, Scope scope, ModelPublication.Source source, Long revision, DefinitionRef binding) {
        tx.requireWritable();
        if (source == ModelPublication.Source.PERSONAL) {
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        }
        var name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.UNAUTHENTICATED));
        var current = permissions.current(name);
        if (!current.contains("admin") && !current.contains("aiConfiguration:manage"))
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        NewAiProperties properties;
        if (source == ModelPublication.Source.DATABASE) {
            if (revision == null || revision <= 0) throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
            var payload = mapper.revision(revision);
            if (payload == null) throw error(PlatformErrorCodeEnum.NOT_FOUND);
            properties = json.read(payload, AiControlData.Catalog.class).properties(fixed, java.util.List.of(), java.util.List.of());
        } else {
            if (revision != null) throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
            properties = fixed;
        }
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
