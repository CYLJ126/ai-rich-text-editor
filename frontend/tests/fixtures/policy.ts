import type {
  PolicyConnectionOptions,
  PolicyKind,
  PolicyRules,
  PolicyScope,
  TenantPolicyView,
  WorkspacePolicyView
} from '@/services/platform/policy';

export const policyScope = {tenantId: 'tenant-policy', workspaceId: 'workspace-policy'};
export const policyRules: PolicyRules = {
  allowedCreationTypes: ['article', 'collection', 'model', 'assistant'],
  extraCreationRoles: {model: [2]},
  guestAiAllowed: false,
  personalCredentialsAllowed: false,
  allowedProtocols: ['existing-protocol'],
  allowedProviders: ['existing-provider'],
  allowedDestinations: ['existing.example'],
  exportTypes: ['article', 'collection'],
  egressTypes: ['article'],
  shareTargetKinds: [0, 1, 2],
};
export const tenantPolicyView: TenantPolicyView = {
  tenantName: '个人租户名称', tenantType: 0, tenantVersion: 8, tenantEpoch: 12,
  policy: {
    tenantId: policyScope.tenantId, rules: policyRules, membersMayCreateWorkspace: true,
    policySchemaVersion: 1, rolePresetVersion: 1, version: 3, personalTemplateVersion: 1
  },
};
export const workspacePolicyView: WorkspacePolicyView = {
  tenantName: tenantPolicyView.tenantName, tenantType: 0, workspaceVersion: 9, workspaceEpoch: 13,
  tenantPolicyVersion: 3, tenantEpoch: 12, effect: {effective: policyRules, limitedFields: []},
  policy: {scope: policyScope, rules: policyRules, policySchemaVersion: 1, rolePresetVersion: 1, version: 4},
};


export const connectionOptions = (kind: PolicyKind, scope: PolicyScope): PolicyConnectionOptions => ({
  tenantId: scope.tenantId,
  scope: kind === 'workspace' && scope.workspaceId ? {tenantId: scope.tenantId, workspaceId: scope.workspaceId} : null,
  protocols: ['chat-completions-sse'], providers: ['deepseek'], hosts: ['api.deepseek.com']
});
