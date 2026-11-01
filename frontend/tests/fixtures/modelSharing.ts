import type {ModelAccess, SharingView} from '@/services/platform/modelSharing';
import type {ManagedModel} from '@/services/platform/model';

export const sharingScope = {tenantId: 'tenant', workspaceId: 'home'};
export const sharingModel: ManagedModel = {
  resourceId: 'tenant-model', scope: sharingScope, name: 'Tenant model', createdBy: 'alice', state: 'PUBLISHED',
  bindingId: 'personal-binding', version: 1, createdAt: 'now', ownershipType: 'TENANT'
};
export const sharingView: SharingView = {
  mayManageScopes: true,
  policy: {
    resourceId: sharingModel.resourceId,
    scope: sharingScope,
    workspaceIds: ['home'],
    credentialVersion: 1,
    subjectIds: [],
    version: 0
  },
  credential: {resourceId: sharingModel.resourceId, state: 'CONFIGURED', version: 1, mayMaintain: true, kind: 'TENANT'}
};
export const sharingAccess: ModelAccess = {
  resource: {tenantId: 'tenant', type: 'model', resourceId: sharingModel.resourceId},
  ownership: {tenantId: 'tenant', workspaceId: null, type: 0}, visibility: 'EXPLICIT', aclVersion: 0
};
export const sharingContext = {userId: 'alice', scope: sharingScope};
