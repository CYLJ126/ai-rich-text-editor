package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.PersonalModels.Release;
import com.arte.ainew.spi.model.PersonalModelStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.platform.persistence.mapper.PlatformPersonalModelMapper;
import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.serialize.CanonicalJson;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.util.List;
import java.util.Optional;

/**
 * 个人发布证据的持久化适配
 * <p>
 * 当前读加入原事务及范围门控，载荷须匹配索引和摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public final class PersonalModelStoreServiceImpl extends PlatformStoreSupport<PlatformModelResourceMapper, PlatformModelResourceDto>
        implements PersonalModelStore {
    private final PlatformPersonalModelMapper mapper;
    private final PlatformJson json;

    public PersonalModelStoreServiceImpl(PlatformModelResourceMapper resources, PlatformPersonalModelMapper mapper,
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

    private Release decode(Scope scope, PlatformPersonalModelMapper.Row row) {
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
        return Optional.ofNullable(mapper.find(scopeKey(scope), bindingKey(scope, binding), current)).map(row -> {
            Release release = decode(scope, row);
            if (!release.binding().equals(binding)) {
                throw new IllegalStateException("Personal binding mismatch");
            }
            return release;
        });
    }

    @Override
    public List<Release> list(Scope scope, boolean current) {
        gate(scope, current);
        List<PlatformPersonalModelMapper.Row> rows = mapper.list(scopeKey(scope), current);
        if (rows.size() > 256) {
            throw new IllegalStateException("Personal publication limit exceeded");
        }
        return rows.stream().map(row -> decode(scope, row)).toList();
    }

    @Override
    public boolean insert(Release release) {
        gate(release.scope(), true);
        String resourceKey = DefaultCommandExecutor.digest(List.of(release.resource().tenantId(), release.resource().type().value(), release.resource().resourceId()));
        return insertRecord(() -> mapper.insert(scopeKey(release.scope()), resourceKey,
                new PlatformPersonalModelMapper.Row(bindingKey(release.scope(), release.binding()), CanonicalJson.digest(release), json.write(release))));
    }
}
