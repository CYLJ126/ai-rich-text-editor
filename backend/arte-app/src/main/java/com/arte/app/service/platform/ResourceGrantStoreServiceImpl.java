package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformResourceAclMapper;
import com.arte.app.pojo.platform.PlatformResourceAccessDto;
import com.arte.app.pojo.platform.PlatformResourceGrantDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.access.GrantTarget;
import com.arte.core.pojo.access.ResourceAccess;
import com.arte.core.pojo.access.ResourceGrant;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ACL 原始存储
 * <p>
 * 父门控、当前读、独立来源唯一键和 CAS，不在此计算允许权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
@Service("resourceGrantStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class ResourceGrantStoreServiceImpl extends PlatformStoreSupport<PlatformResourceAclMapper, PlatformResourceAccessDto> implements ResourceGrantStore {

    private final PlatformJson json;

    public ResourceGrantStoreServiceImpl(@Qualifier("platformResourceAclMapper") PlatformResourceAclMapper mapper,
                                         @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        super(mapper, tx);
        this.json = json;
    }

    private static String key(ResourceRef ref) {
        return DefaultCommandExecutor.digest(List.of(ref.tenantId(), ref.type().value(), ref.resourceId()));
    }

    private String sourceKey(ResourceGrant g) {
        var fields = new ArrayList<String>();
        fields.add(key(g.resource()));
        fields.add(g.action().value());
        fields.add(g.source().name());
        fields.add(g.sourceOperationId());
        switch (g.target()) {
            case GrantTarget.Subject s -> {
                fields.add("SUBJECT");
                fields.add(s.subjectId());
                fields.add(s.scope().tenantId());
                fields.add(s.scope().workspaceId());
                fields.add(Long.toString(s.membershipGeneration()));
            }
            case GrantTarget.SpaceRole s -> {
                fields.add("SPACE_ROLE");
                fields.add(s.scope().tenantId());
                fields.add(s.scope().workspaceId());
                fields.add(s.role().name());
            }
            case GrantTarget.Space s -> {
                fields.add("WORKSPACE");
                fields.add(s.scope().tenantId());
                fields.add(s.scope().workspaceId());
            }
        }
        return DefaultCommandExecutor.digest(fields);
    }

    private ResourceAccess decode(PlatformResourceAccessDto row, ResourceRef ref) {
        var value = json.read(row.getPayload(), ResourceAccess.class);
        if (!value.resource().equals(ref) || !row.getResourceKey().equals(key(ref)) || !row.getTenantId().equals(ref.tenantId())
                || !row.getResourceType().equals(ref.type().value()) || !row.getResourceId().equals(ref.resourceId())
                || !Objects.equals(row.getWorkspaceId(), value.ownership().workspaceId()) || row.getAclVersion() != value.aclVersion())
            throw new IllegalStateException("ACL identity collision or corrupt envelope");
        return value;
    }

    private ResourceGrant decode(PlatformResourceGrantDto row, ResourceRef ref) {
        var value = json.read(row.getPayload(), ResourceGrant.class);
        if (!value.resource().equals(ref) || !row.getResourceKey().equals(key(ref)) || !row.getId().equals(value.id())
                || !row.getSourceIdentityDigest().equals(sourceKey(value)) || row.getVersion() != value.version())
            throw new IllegalStateException("Grant identity collision or corrupt envelope");
        return value;
    }

    private PlatformResourceAccessDto encode(ResourceAccess value) {
        var row = new PlatformResourceAccessDto();
        row.setResourceKey(key(value.resource()));
        row.setTenantId(value.resource().tenantId());
        row.setResourceType(value.resource().type().value());
        row.setResourceId(value.resource().resourceId());
        row.setWorkspaceId(value.ownership().workspaceId());
        row.setPayload(json.write(value));
        row.setAclVersion(value.aclVersion());
        return row;
    }

    private PlatformResourceGrantDto encode(ResourceGrant value) {
        var row = new PlatformResourceGrantDto();
        row.setId(value.id());
        row.setResourceKey(key(value.resource()));
        row.setSourceIdentityDigest(sourceKey(value));
        row.setPayload(json.write(value));
        row.setVersion(value.version());
        return row;
    }

    private void gate(ResourceRef ref) {
        tx.requireWritable();
        requireTenant(ref.tenantId());
    }

    @Override
    public Optional<ResourceAccess> findAccess(ResourceRef ref) {
        return Optional.ofNullable(baseMapper.access(key(ref), false)).map(row -> decode(row, ref));
    }

    @Override
    public Optional<ResourceAccess> lockAccess(ResourceRef ref) {
        gate(ref);
        return Optional.ofNullable(baseMapper.access(key(ref), true)).map(row -> decode(row, ref));
    }

    @Override
    public boolean insertAccess(ResourceAccess value) {
        gate(value.resource());
        if (value.ownership().workspaceId() != null)
            requireScope(new Scope(value.ownership().tenantId(), value.ownership().workspaceId()));
        if (value.aclVersion() != 0) throw new IllegalArgumentException("Initial ACL version must be zero");
        return insertRecord(() -> baseMapper.insertAccess(encode(value)));
    }

    @Override
    public boolean replaceAccess(ResourceAccess value, long expected) {
        gate(value.resource());
        next(value.aclVersion(), expected);
        var old = lockAccess(value.resource()).orElseThrow();
        if (!old.ownership().equals(value.ownership()))
            throw new IllegalArgumentException("Resource ownership is immutable");
        return updateRecord(() -> baseMapper.replaceAccess(encode(value), expected));
    }

    @Override
    public PageResult<ResourceGrant> findGrants(ResourceRef ref, PageQuery query) {
        return page(ref, query, false);
    }

    @Override
    public PageResult<ResourceGrant> findCurrentGrants(ResourceRef ref, PageQuery query) {
        gate(ref);
        return page(ref, query, true);
    }

    private PageResult<ResourceGrant> page(ResourceRef ref, PageQuery query, boolean current) {
        // 当前读计数不能使用 RR 普通 COUNT 的旧快照；锁定有界主键集合后计数。
        long count = current ? baseMapper.currentGrantIds(key(ref)).size() : baseMapper.countGrants(key(ref));
        if (count > 10000) {
            throw new IllegalStateException("ACL grant scan budget exceeded");
        }
        List<PlatformResourceGrantDto> rows = baseMapper.grants(key(ref), query.size(), Math.multiplyExact(query.current() - 1, query.size()), current);
        return new PageResult<>(query.current(), query.size(), count, rows.stream().map(row -> decode(row, ref)).toList());
    }

    @Override
    public Optional<ResourceGrant> findGrant(ResourceRef ref, String id) {
        return Optional.ofNullable(baseMapper.grant(key(ref), id, false)).map(row -> decode(row, ref));
    }

    @Override
    public Optional<ResourceGrant> findCurrentGrant(ResourceRef ref, String id) {
        gate(ref);
        return Optional.ofNullable(baseMapper.grant(key(ref), id, true)).map(row -> decode(row, ref));
    }

    @Override
    public boolean insertGrantIfAbsent(ResourceGrant value) {
        gate(value.resource());
        if (value.version() != 0) throw new IllegalArgumentException("Initial grant version must be zero");
        lockAccess(value.resource()).orElseThrow();
        return insertRecord(() -> baseMapper.insertGrant(encode(value)));
    }

    @Override
    public boolean replaceGrant(ResourceGrant value, long expected) {
        gate(value.resource());
        next(value.version(), expected);
        var old = findCurrentGrant(value.resource(), value.id()).orElseThrow();
        if (!old.target().equals(value.target()) || !old.action().equals(value.action()) || old.source() != value.source()
                || !old.sourceOperationId().equals(value.sourceOperationId()) || !old.issuedBy().equals(value.issuedBy())
                || !old.createdAt().equals(value.createdAt()) || !Objects.equals(old.expiresAt(), value.expiresAt())
                || old.state() != com.arte.core.enums.access.GrantStateEnum.ACTIVE || value.state() != com.arte.core.enums.access.GrantStateEnum.REVOKED)
            throw new IllegalArgumentException("Only ACTIVE to REVOKED transition is allowed");
        return updateRecord(() -> baseMapper.replaceGrant(encode(value), expected));
    }
}
