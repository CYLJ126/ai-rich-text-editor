package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.app.pojo.platform.PlatformModelVersionDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 管理身份与不可变版本的真实存储；逻辑身份摘要只用于索引，不作为授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
@Service
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class ModelResourceStoreServiceImpl extends PlatformStoreSupport<PlatformModelResourceMapper, PlatformModelResourceDto> implements ModelResourceStore {

    private final PlatformJson json;

    public ModelResourceStoreServiceImpl(@Qualifier("platformModelResourceMapper") PlatformModelResourceMapper mapper,
                                         @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        super(mapper, tx);
        this.json = json;
    }

    private static String resourceKey(ResourceRef r) {
        if (!r.type().equals(MODEL)) throw new IllegalArgumentException("Not a model resource");
        return DefaultCommandExecutor.digest(List.of(r.tenantId(), r.type().value(), r.resourceId()));
    }

    private static String bindingKey(String tenant, DefinitionRef ref) {
        ref.requireType("binding");
        return DefaultCommandExecutor.digest(List.of(tenant, ref.type(), ref.id(), ref.version()));
    }

    private static String definitionKey(DefinitionRef ref) {
        return DefaultCommandExecutor.digest(List.of(ref.type(), ref.id(), ref.version()));
    }

    private void gate(String tenant) {
        tx.requireWritable();
        requireTenant(tenant);
    }

    private PlatformModelResourceDto encode(ModelResource modelResource) {
        PlatformModelResourceDto row = new PlatformModelResourceDto();
        row.setResourceKey(resourceKey(modelResource.resource()));
        row.setTenantId(modelResource.resource().tenantId());
        row.setResourceId(modelResource.resource().resourceId());
        row.setWorkspaceId(modelResource.ownership().workspaceId());
        row.setVersion(modelResource.version());
        row.setPayload(json.write(modelResource));
        return row;
    }

    private ModelResource decode(PlatformModelResourceDto row, ResourceRef ref) {
        ModelResource modelResource = json.read(row.getPayload(), ModelResource.class);
        if (!modelResource.resource().equals(ref) || !row.getResourceKey().equals(resourceKey(ref)) || !row.getTenantId().equals(ref.tenantId())
                || !row.getResourceId().equals(ref.resourceId()) || !Objects.equals(row.getWorkspaceId(), modelResource.ownership().workspaceId()) || row.getVersion() != modelResource.version()) {
            throw conflict();
        }
        return modelResource;
    }

    private static String snapshotDigest(ModelResourceVersion v) {
        ModelPublication modelPublication = v.publication();
        return DefaultCommandExecutor.digest(Arrays.asList(resourceKey(v.resource()), modelPublication.contentDigest(), modelPublication.source().name(),
                modelPublication.catalogRevision() == null ? null : modelPublication.catalogRevision().toString(), modelPublication.releaseRef(), v.linkedBy(), v.linkedAt().toString()));
    }

    private ModelResourceVersion decode(PlatformModelVersionDto row, String tenant, DefinitionRef binding) {
        ModelResourceVersion modelResourceVersion = json.read(row.getPayload(), ModelResourceVersion.class);
        if (!modelResourceVersion.resource().tenantId().equals(tenant) || !modelResourceVersion.publication().binding().definition().equals(binding)
                || !row.getBindingKey().equals(bindingKey(tenant, binding)) || !row.getTenantId().equals(tenant)
                || !row.getBindingId().equals(binding.id()) || !row.getBindingVersion().equals(binding.version())
                || !row.getResourceKey().equals(resourceKey(modelResourceVersion.resource()))
                || !snapshotDigest(modelResourceVersion).equals(row.getSnapshotDigest())) {
            throw conflict();
        }
        return modelResourceVersion;
    }

    @Override
    public Optional<ModelResource> find(ResourceRef ref) {
        return Optional.ofNullable(baseMapper.resource(resourceKey(ref), false)).map(row -> decode(row, ref));
    }

    @Override
    public Optional<ModelResource> findCurrent(ResourceRef ref) {
        gate(ref.tenantId());
        return Optional.ofNullable(baseMapper.resource(resourceKey(ref), true)).map(row -> decode(row, ref));
    }

    @Override
    public boolean insert(ModelResource modelResource) {
        gate(modelResource.resource().tenantId());
        requireScope(new Scope(modelResource.ownership().tenantId(), modelResource.ownership().workspaceId()));
        if (modelResource.version() != 0 || modelResource.state() != ModelResource.State.DRAFT) {
            throw new IllegalArgumentException("New resource must be a draft");
        }
        return insertRecord(() -> baseMapper.insertResource(encode(modelResource)));
    }

    @Override
    public boolean replace(ModelResource modelResource, long expected) {
        gate(modelResource.resource().tenantId());
        next(modelResource.version(), expected);
        ModelResource old = findCurrent(modelResource.resource()).orElseThrow();
        if (!old.ownership().equals(modelResource.ownership()) || !old.createdBy().equals(modelResource.createdBy()) || !old.createdAt().equals(modelResource.createdAt())
                || !old.name().equals(modelResource.name()) || old.bindingId() != null && !old.bindingId().equals(modelResource.bindingId())
                || modelResource.state() == ModelResource.State.DRAFT) {
            throw new IllegalArgumentException("Immutable model identity changed");
        }
        return updateRecord(() -> baseMapper.replaceResource(encode(modelResource), expected));
    }

    @Override
    public Optional<ModelResourceVersion> findBinding(String tenant, DefinitionRef binding) {
        return Optional.ofNullable(baseMapper.binding(bindingKey(tenant, binding), false)).map(row -> decode(row, tenant, binding));
    }

    @Override
    public Optional<ModelResourceVersion> findCurrentBinding(String tenant, DefinitionRef binding) {
        gate(tenant);
        return Optional.ofNullable(baseMapper.binding(bindingKey(tenant, binding), true)).map(row -> decode(row, tenant, binding));
    }

    @Override
    public PageResult<ModelResourceVersion> versions(ResourceRef ref, PageQuery query) {
        gate(ref.tenantId());
        long total = baseMapper.currentVersionKeys(resourceKey(ref)).size();
        if (total > 10000) throw new IllegalStateException("Model version scan limit exceeded");
        var values = baseMapper.versions(resourceKey(ref), query.size(), Math.multiplyExact(query.current() - 1, query.size())).stream().map(row -> {
            var v = decode(row, ref.tenantId(), new DefinitionRef("binding", row.getBindingId(), row.getBindingVersion()));
            if (!v.resource().equals(ref)) throw conflict();
            return v;
        }).toList();
        return new PageResult<>(query.current(), query.size(), total, values);
    }

    @Override
    public boolean insertVersion(ModelResourceVersion v) {
        gate(v.resource().tenantId());
        var root = findCurrent(v.resource()).orElseThrow();
        requireScope(new Scope(root.ownership().tenantId(), root.ownership().workspaceId()));
        if (root.bindingId() != null && !root.bindingId().equals(v.publication().binding().definition().id()))
            throw conflict();
        var definitions = v.publication().definitionDigests().entrySet().stream().sorted(Comparator.comparing((Map.Entry<DefinitionRef, String> e) -> e.getKey().type())
                .thenComparing(e -> e.getKey().id()).thenComparing(e -> e.getKey().version())).toList();
        for (var entry : definitions) {
            var ref = entry.getKey();
            var key = definitionKey(ref);
            var source = v.publication().source().code();
            // 先尝试唯一插入再读冲突行，避免缺失行共享 gap 锁升级的竞争。
            if (!insertRecord(() -> baseMapper.insertAuthority(key, ref.type(), ref.id(), ref.version(), source, entry.getValue()))) {
                var old = baseMapper.authority(key);
                if (old == null || !ref.type().equals(old.get("definitionType")) || !ref.id().equals(old.get("definitionId"))
                        || !ref.version().equals(old.get("definitionVersion")) || !entry.getValue().equals(old.get("digest")))
                    throw conflict();
                // 绑定的内容权威不能在 FIXED/DATABASE 间切换；相同依赖可引用其已登记权威，不改写来源。
                if (ref.type().equals("binding") && (!(old.get("source") instanceof Number number) || source != number.intValue()))
                    throw conflict();
            }
        }
        var row = new PlatformModelVersionDto();
        var binding = v.publication().binding().definition();
        row.setBindingKey(bindingKey(v.resource().tenantId(), binding));
        row.setResourceKey(resourceKey(v.resource()));
        row.setTenantId(v.resource().tenantId());
        row.setBindingId(binding.id());
        row.setBindingVersion(binding.version());
        row.setSnapshotDigest(snapshotDigest(v));
        row.setPayload(json.write(v));
        return insertRecord(() -> baseMapper.insertVersion(row));
    }

    private static PlatformException conflict() {
        return new PlatformException(PlatformErrorCodeEnum.IDENTITY_CONFLICT, "model-definition");
    }
}
