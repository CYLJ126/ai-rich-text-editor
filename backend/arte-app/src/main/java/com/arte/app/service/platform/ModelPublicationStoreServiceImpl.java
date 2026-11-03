package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.ModelPublications.Release;
import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.ModelSharingStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelPublicationMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.serialize.CanonicalJson;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.util.List;
import java.util.Optional;

/**
 * 资源发布证据的持久化适配
 * <p>
 * 当前读加入原事务及范围门控，载荷须匹配索引和摘要。
 * 继承的 MyBatis-Plus 方法含事务注解，Spring 会创建 CGLIB 子类代理，因此本类不能声明为 final。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public class ModelPublicationStoreServiceImpl extends PlatformStoreSupport<PlatformModelResourceMapper, PlatformModelResourceDto> implements ModelPublicationStore {

    private final PlatformModelPublicationMapper mapper;
    private final PlatformJson json;
    private ModelSharingStore sharing;
    private ModelResourceStore models;

    public void sharing(ModelSharingStore sharing, ModelResourceStore models) {
        this.sharing = sharing;
        this.models = models;
    }

    public ModelPublicationStoreServiceImpl(PlatformModelResourceMapper resources, PlatformModelPublicationMapper mapper,
                                            PublicTransactionRunner tx, PlatformJson json) {
        super(resources, tx);
        this.mapper = mapper;
        this.json = json;
    }

    private String scopeKey(Scope scope) {
        return DefaultCommandExecutor.digest(List.of(scope.tenantId(), scope.workspaceId()));
    }

    private String bindingKey(Scope scope, DefinitionRef ref) {
        return DefaultCommandExecutor.digest(List.of(scope.tenantId(), ref.type(), ref.id(), ref.version()));
    }

    private void gate(Scope scope, boolean current) {
        tx.requireActive();
        if (current) {
            tx.requireWritable();
            requireScope(scope);
        }
    }

    private Release decode(Scope scope, PlatformModelPublicationMapper.Row row) {
        Release value = json.read(row.payload(), Release.class);
        if (!value.scope().equals(scope) || !bindingKey(scope, value.binding()).equals(row.bindingKey())
                || !CanonicalJson.digest(value).equals(row.digest())) {
            throw new IllegalStateException("Personal publication evidence mismatch");
        }
        return value;
    }

    @Override
    public Optional<Release> find(Scope scope, DefinitionRef binding, boolean current) {
        gate(scope, current);
        if (sharing != null) {
            sharing.validateTenant(scope.tenantId(), current);
        }
        PlatformModelPublicationMapper.Row row = mapper.find(scopeKey(scope), bindingKey(scope, binding), current);
        if (row == null && sharing != null) {
            row = mapper.findShared(scope.tenantId(), scope.workspaceId(), bindingKey(scope, binding), current);
        }
        return Optional.ofNullable(row).map(found -> {
            Release release = decodeForUse(scope, found, current);
            if (!release.binding().equals(binding)) {
                throw new IllegalStateException("Personal binding mismatch");
            }
            return release;
        });
    }

    @Override
    public List<Release> list(Scope scope, boolean current) {
        gate(scope, current);
        if (sharing != null) {
            sharing.validateTenant(scope.tenantId(), current);
        }
        java.util.Map<String, Release> values = new java.util.LinkedHashMap<>();
        List<PlatformModelPublicationMapper.Row> rows = new java.util.ArrayList<>(mapper.list(scopeKey(scope), current));
        if (sharing != null) {
            rows.addAll(mapper.listShared(scope.tenantId(), scope.workspaceId(), current));
        }
        for (PlatformModelPublicationMapper.Row row : rows) {
            Release release = decodeForUse(scope, row, current);
            Release previous = values.putIfAbsent(row.bindingKey(), release);
            if (previous != null && !previous.equals(release)) {
                throw new IllegalStateException("Conflicting shared release");
            }
        }
        if (values.size() > 256) {
            throw new IllegalStateException("Personal publication limit exceeded");
        }
        return List.copyOf(values.values());
    }

    private Release decodeForUse(Scope scope, PlatformModelPublicationMapper.Row row, boolean current) {
        Release release = json.read(row.payload(), Release.class);
        decode(release.scope(), row);
        if (!release.resource().tenantId().equals(scope.tenantId())) {
            throw new IllegalStateException("Cross tenant release");
        }
        if (sharing != null) {
            com.arte.ainew.pojo.model.ModelResource model = (current ? models.findCurrent(release.resource()) : models.find(release.resource())).orElseThrow();
            if (model.ownership().workspaceId() == null) {
                com.arte.ainew.pojo.model.ModelSharingPolicy policy = sharing.find(model.resource(), current).orElseThrow();
                if (!model.managementScope().equals(release.scope()) || !policy.managementScope().equals(release.scope())
                        || !scope.equals(release.scope()) && !policy.workspaceIds().contains(scope.workspaceId())) {
                    throw new IllegalStateException("Invalid tenant model use scope");
                }
                return release;
            }
        }
        if (!release.scope().equals(scope)) {
            throw new IllegalStateException("Workspace publication cannot cross scopes");
        }
        return release;
    }

    @Override
    public boolean insert(Release release) {
        gate(release.scope(), true);
        String resourceKey = DefaultCommandExecutor.digest(List.of(release.resource().tenantId(), release.resource().type().value(), release.resource().resourceId()));
        return insertRecord(() -> mapper.insert(scopeKey(release.scope()), resourceKey,
                new PlatformModelPublicationMapper.Row(bindingKey(release.scope(), release.binding()), CanonicalJson.digest(release), json.write(release))));
    }
}
