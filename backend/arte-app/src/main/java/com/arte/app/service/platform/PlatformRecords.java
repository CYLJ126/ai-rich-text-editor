package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.pojo.platform.*;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.*;

import java.time.Instant;
import java.util.Set;

/**
 * core 值对象和 app PO/DTO 的显式转换，保持稳定主体、完整范围和 Instant 纳秒精度。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
final class PlatformRecords {

    private PlatformRecords() {
    }

    public record Favorites(Set<Scope> scopes) {
    }

    private static Scope nullable(String tenant, String space) {
        return tenant == null && space == null ? null : new Scope(tenant, space);
    }

    private static String tenant(Scope scope) {
        return scope == null ? null : scope.tenantId();
    }

    private static String space(Scope scope) {
        return scope == null ? null : scope.workspaceId();
    }

    static PlatformTenantDto tenant(Tenant v) {
        var row = new PlatformTenantDto();
        row.setId(v.id());
        row.setName(v.name());
        row.setType(v.type());
        row.setPersonalSubjectId(v.personalSubjectId());
        row.setState(v.state());
        row.setCreatedBy(v.createdBy());
        row.setVersion(v.version());
        row.setAuthEpoch(v.authEpoch());
        row.setCreatedSec(v.createdAt().getEpochSecond());
        row.setCreatedNano(v.createdAt().getNano());
        row.setUpdatedSec(v.updatedAt().getEpochSecond());
        row.setUpdatedNano(v.updatedAt().getNano());
        return row;
    }

    static Tenant tenant(PlatformTenantDto v) {
        return new Tenant(v.getId(), v.getName(), v.getType(), v.getPersonalSubjectId(), v.getState(), v.getCreatedBy(), v.getVersion(), v.getAuthEpoch(), Instant.ofEpochSecond(v.getCreatedSec(), v.getCreatedNano()), Instant.ofEpochSecond(v.getUpdatedSec(), v.getUpdatedNano()));
    }

    static PlatformWorkspaceDto workspace(Workspace v) {
        var row = new PlatformWorkspaceDto();
        row.setTenantId(v.scope().tenantId());
        row.setId(v.scope().workspaceId());
        row.setName(v.name());
        row.setDescription(v.description());
        row.setState(v.state());
        row.setCreatedBy(v.createdBy());
        row.setVersion(v.version());
        row.setAuthEpoch(v.authEpoch());
        row.setCreatedSec(v.createdAt().getEpochSecond());
        row.setCreatedNano(v.createdAt().getNano());
        row.setUpdatedSec(v.updatedAt().getEpochSecond());
        row.setUpdatedNano(v.updatedAt().getNano());
        return row;
    }

    static Workspace workspace(PlatformWorkspaceDto v) {
        return new Workspace(new Scope(v.getTenantId(), v.getId()), v.getName(), v.getDescription(), v.getState(), v.getCreatedBy(), v.getVersion(), v.getAuthEpoch(), Instant.ofEpochSecond(v.getCreatedSec(), v.getCreatedNano()), Instant.ofEpochSecond(v.getUpdatedSec(), v.getUpdatedNano()));
    }

    static PlatformTenantMemberDto tenantMember(TenantMembership v) {
        var row = new PlatformTenantMemberDto();
        row.setId(v.id());
        row.setTenantId(v.tenantId());
        row.setSubjectId(v.subjectId());
        row.setRole(v.role());
        row.setState(v.state());
        row.setGeneration(v.generation());
        row.setVersion(v.version());
        return row;
    }

    static TenantMembership tenantMember(PlatformTenantMemberDto v) {
        return new TenantMembership(v.getId(), v.getTenantId(), v.getSubjectId(), v.getRole(), v.getState(), v.getGeneration(), v.getVersion());
    }

    static PlatformWorkspaceMemberDto workspaceMember(WorkspaceMembership v) {
        var row = new PlatformWorkspaceMemberDto();
        row.setId(v.id());
        row.setTenantId(v.scope().tenantId());
        row.setWorkspaceId(v.scope().workspaceId());
        row.setSubjectId(v.subjectId());
        row.setSubjectDigest(PlatformStoreSupport.subjectDigest(v.subjectId()));
        row.setRole(v.role());
        row.setState(v.state());
        row.setGeneration(v.generation());
        row.setVersion(v.version());
        return row;
    }

    static WorkspaceMembership workspaceMember(PlatformWorkspaceMemberDto v) {
        return new WorkspaceMembership(v.getId(), new Scope(v.getTenantId(), v.getWorkspaceId()), v.getSubjectId(), v.getRole(), v.getState(), v.getGeneration(), v.getVersion());
    }

    static PlatformTenantPolicyDto tenantPolicy(TenantPolicy v, PlatformJson json) {
        var row = new PlatformTenantPolicyDto();
        row.setTenantId(v.tenantId());
        row.setRules(json.write(v.rules()));
        row.setMembersMayCreateWorkspace(v.membersMayCreateWorkspace());
        row.setPolicySchemaVersion(v.policySchemaVersion());
        row.setRolePresetVersion(v.rolePresetVersion());
        row.setVersion(v.version());
        return row;
    }

    static TenantPolicy tenantPolicy(PlatformTenantPolicyDto v, PlatformJson json) {
        return new TenantPolicy(v.getTenantId(), json.read(v.getRules(), PolicyRules.class), v.getMembersMayCreateWorkspace(), v.getPolicySchemaVersion(), v.getRolePresetVersion(), v.getVersion());
    }

    static PlatformWorkspacePolicyDto workspacePolicy(WorkspacePolicy v, PlatformJson json) {
        var row = new PlatformWorkspacePolicyDto();
        row.setTenantId(v.scope().tenantId());
        row.setWorkspaceId(v.scope().workspaceId());
        row.setRules(json.write(v.rules()));
        row.setPolicySchemaVersion(v.policySchemaVersion());
        row.setRolePresetVersion(v.rolePresetVersion());
        row.setVersion(v.version());
        return row;
    }

    static WorkspacePolicy workspacePolicy(PlatformWorkspacePolicyDto v, PlatformJson json) {
        return new WorkspacePolicy(new Scope(v.getTenantId(), v.getWorkspaceId()), json.read(v.getRules(), PolicyRules.class), v.getPolicySchemaVersion(), v.getRolePresetVersion(), v.getVersion());
    }

    static PlatformWorkspacePreferenceDto preference(WorkspacePreference v, PlatformJson json) {
        var row = new PlatformWorkspacePreferenceDto();
        row.setSubjectId(v.subjectId());
        row.setDefaultTenantId(tenant(v.defaultScope()));
        row.setDefaultWorkspaceId(space(v.defaultScope()));
        row.setStartupTenantId(tenant(v.startupScope()));
        row.setStartupWorkspaceId(space(v.startupScope()));
        row.setLastTenantId(tenant(v.lastScope()));
        row.setLastWorkspaceId(space(v.lastScope()));
        row.setFavorites(json.write(new Favorites(v.favorites())));
        row.setVersion(v.version());
        return row;
    }

    static WorkspacePreference preference(PlatformWorkspacePreferenceDto v, PlatformJson json) {
        return new WorkspacePreference(v.getSubjectId(), new Scope(v.getDefaultTenantId(), v.getDefaultWorkspaceId()), nullable(v.getStartupTenantId(), v.getStartupWorkspaceId()), nullable(v.getLastTenantId(), v.getLastWorkspaceId()), json.read(v.getFavorites(), Favorites.class).scopes(), v.getVersion());
    }
}
