package com.arte.app.security.bridge;

import com.arte.app.common.enums.richtext.ArticlePermissionEnum;
import com.arte.app.common.enums.richtext.CatalogPermissionEnum;
import com.arte.app.pojo.richtext.ShareDto;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.CommonResourceAction;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 复用现有分享查询和权限枚举，显式传入账号；不调用依赖 ThreadLocal 的旧 PermissionValidator。
 */
public class LegacyResourcePermissions {
    private final JdbcTemplate jdbc;
    private final ExistingShareQueries shares;
    private final JdbcSecurityRepository repository;

    public LegacyResourcePermissions(JdbcTemplate jdbc, ExistingShareQueries shares, JdbcSecurityRepository repository) {
        this.jdbc = jdbc;
        this.shares = shares;
        this.repository = repository;
    }

    public record Snapshot(boolean readable, boolean commentable, boolean writable, boolean manageable,
                           String revision) {
        boolean permits(CommonResourceAction action) {
            return switch (action) {
                case READ -> readable;
                case COMMENT, ANNOTATE -> commentable;
                case EDIT -> writable;
                case MANAGE_SHARING -> manageable;
                // 独立动作还必须经过新资源授权记录；旧权限仅提供资料可读前提。
                default -> readable;
            };
        }
    }

    /**
     * null 表示资源不存在／已删除；未知类型与非法分享值不能隐式按可读处理。
     */
    public Snapshot snapshot(ResourceRef resource, String userName) {
        Integer id = JdbcSecurityRepository.positiveId(resource.resourceId());
        if (id == null) return null;
        boolean article = "ARTICLE".equals(resource.resourceType());
        if (!article && !"CATALOG".equals(resource.resourceType()))
            throw new IllegalArgumentException("unsupported resource type");
        String sql = article
                ? "SELECT create_by, is_public, catalog_id FROM arte_rt_article WHERE id = ? AND is_delete = 0"
                : "SELECT create_by, is_public, father_id FROM arte_rt_catalog WHERE id = ? AND is_delete = 0";
        var rows = jdbc.query(sql, (rs, row) -> new Metadata(rs.getString("create_by"), rs.getBoolean("is_public"),
                rs.getObject(article ? "catalog_id" : "father_id", Integer.class)), id);
        if (rows.isEmpty()) return null;
        var metadata = rows.getFirst();
        boolean owner = userName.equals(metadata.owner());
        String metadataRevision = SecurityFingerprints.values(metadata.owner(), Boolean.toString(metadata.publicResource()),
                String.valueOf(metadata.parentId()), Boolean.toString(owner));
        if (owner) return new Snapshot(true, true, true, true, metadataRevision);
        var placement = repository.placement(resource).orElseThrow(() -> new IllegalStateException("resource scope unavailable"));
        List<ShareDto> effective = article ? shares.article(id, metadata.parentId(), userName)
                : shares.catalog(id, userName);
        if (effective == null) throw new IllegalStateException("share query unavailable");
        boolean read = owner || metadata.publicResource(), comment = owner, write = owner, manage = owner;
        var versions = new ArrayList<String>();
        for (var share : effective) {
            if (share == null) throw new IllegalStateException("invalid share");
            // 现有递归查询从当前关系表核对用户／角色；再核对资源及角色的启用状态。
            if (!activeSharedResource(share)) continue;
            var sharedPlacement = repository.placement(ResourceRef.current(share.getResourceType(), String.valueOf(share.getResourceId())))
                    .orElseThrow(() -> new IllegalStateException("inherited share scope unavailable"));
            if (!sharedPlacement.enabled() || !sharedPlacement.tenantId().equals(placement.tenantId())
                    || !sharedPlacement.workspaceId().equals(placement.workspaceId())) {
                throw new IllegalStateException("inherited share crosses resource scope");
            }
            if ("ROLE".equals(share.getTargetType()) && !activeRole(share.getTargetRole())) continue;
            String permission = share.getPermission();
            if (article) {
                permission = "CATALOG".equals(share.getResourceType()) ? share.getArticlePermission() : permission;
                if (permission == null && "CATALOG".equals(share.getResourceType())) permission = "READ";
                if (!ArticlePermissionEnum.isValid(permission))
                    throw new IllegalStateException("unknown article permission");
                var value = ArticlePermissionEnum.of(permission);
                comment |= value.canComment();
                write |= value.canWrite();
                manage |= value.canDeleteOrGrant();
            } else {
                if (!CatalogPermissionEnum.isValid(permission))
                    throw new IllegalStateException("unknown catalog permission");
                var value = CatalogPermissionEnum.of(permission);
                comment |= value.canDeleteOrGrant();
                write |= value.canDeleteOrGrant();
                manage |= value.canDeleteOrGrant();
            }
            read = true;
            versions.add(SecurityFingerprints.values(String.valueOf(share.getId()), share.getResourceType(),
                    String.valueOf(share.getResourceId()), share.getTargetType(), share.getTargetUser(),
                    share.getTargetRole(), permission));
        }
        versions.sort(String::compareTo);
        versions.addFirst(metadataRevision);
        return new Snapshot(read, comment, write, manage, SecurityFingerprints.values(versions.toArray(String[]::new)));
    }

    private boolean activeRole(String code) {
        return !jdbc.queryForList("SELECT id FROM arte_rbac_role WHERE role_code = ? AND status = '1'", code).isEmpty();
    }

    private boolean activeSharedResource(ShareDto share) {
        String table = switch (share.getResourceType()) {
            case "ARTICLE" -> "arte_rt_article";
            case "CATALOG" -> "arte_rt_catalog";
            default -> throw new IllegalStateException("unknown share resource type");
        };
        return !jdbc.queryForList("SELECT id FROM " + table + " WHERE id = ? AND is_delete = 0", share.getResourceId()).isEmpty();
    }

    private record Metadata(String owner, boolean publicResource, Integer parentId) {
    }
}
