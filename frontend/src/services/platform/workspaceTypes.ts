/** Public platform wire types. Governance roles do not imply AI/resource permission. */
export interface WorkspaceScope {
  tenantId: string;
  workspaceId: string
}

export type WorkspaceRole = 0 | 1 | 2 | 3;
export type WorkspaceState = 0 | 1 | 2 | 3;

export interface WorkspaceView {
  scope: WorkspaceScope;
  name: string;
  description?: string;
  state: WorkspaceState;
  currentRole: WorkspaceRole;
  version: number;
}

export interface WorkspaceSummary extends WorkspaceView {
  defaultWorkspace: boolean
}

export interface WorkspacePreference {
  defaultScope: WorkspaceScope;
  startupScope: WorkspaceScope | null;
  lastScope: WorkspaceScope | null;
  favorites: WorkspaceScope[];
  version: number;
}

export interface CommandReceipt<T> {
  operationId: string;
  status: 0 | 1;
  delivery: 0 | 1;
  value: T;
  resultVersion: number;
  acceptedAt: string;
  completedAt: string | null;
}

export interface TenantView {
  id: string;
  name: string;
  type: 0 | 1;
  state: 0 | 1 | 2;
  currentRole: WorkspaceRole;
  version: number;
}

export interface PersonalWorkspace {
  tenant: TenantView;
  workspace: WorkspaceView;
  preference: WorkspacePreference;
}

export interface CreateWorkspaceRequest {
  tenantId: string;
  name: string;
  description: string
}

export interface RenameWorkspaceRequest {
  scope: WorkspaceScope;
  expectedVersion: number;
  name: string;
  description: string
}

export interface UpdateWorkspacePreferenceRequest {
  expectedPreferenceVersion: number;
  startupScope: WorkspaceScope | null;
  lastScope: WorkspaceScope | null;
  favorites: WorkspaceScope[];
}

export interface WorkspaceAccessResponse {
  scope?: { tenantId: string; workspaceId?: string };
  resourceType?: 'tenant' | 'workspace';
  action?: string;
  allowed: boolean;
  reason: string;
  checkedAt: string;
}
