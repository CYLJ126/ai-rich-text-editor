import {type AiRequestOptions, postPage, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import type {AiScope} from '@/services/arte-ai';

export type WorkspaceScope = AiScope;

// Public core enums use numeric wire codes, rather than Java enum names.
export interface WorkspaceView {
  scope: WorkspaceScope;
  name: string;
  description?: string;
  state: 0 | 1 | 2 | 3;
  currentRole: 0 | 1 | 2 | 3;
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

export interface PersonalWorkspace {
  tenant: { id: string; name: string; type: number; currentRole: number; version: number };
  workspace: WorkspaceView;
  preference: WorkspacePreference;
}

const base = '/arte/workspace';
const result = <T, >(path: string, data: unknown, options?: AiRequestOptions, key?: string) => postResult<T>(path, data, options, key, base);
export const initializePersonalWorkspace = (key: string, options?: AiRequestOptions) => result<CommandReceipt<PersonalWorkspace>>('initializePersonalWorkspace', undefined, options, requireIdempotencyKey(key));
export const listAccessibleWorkspaces = (current = 1, options?: AiRequestOptions) => postPage<WorkspaceSummary>('listAccessibleWorkspaces', {
  current,
  size: 20
}, options, base);
export const getWorkspace = (scope: WorkspaceScope, options?: AiRequestOptions) => result<WorkspaceView>('getWorkspace', {scope}, options);
export const createWorkspace = (data: {
  tenantId: string;
  name: string;
  description: string
}, key: string, options?: AiRequestOptions) => result<CommandReceipt<WorkspaceView>>('createWorkspace', data, options, requireIdempotencyKey(key));
export const getWorkspacePreference = (options?: AiRequestOptions) => result<WorkspacePreference>('getWorkspacePreference', undefined, options);
export const updateWorkspacePreference = (data: {
  expectedPreferenceVersion: number;
  startupScope: WorkspaceScope | null;
  lastScope: WorkspaceScope | null;
  favorites: WorkspaceScope[]
}, key: string, options?: AiRequestOptions) => result<CommandReceipt<WorkspacePreference>>('updateWorkspacePreference', data, options, requireIdempotencyKey(key));
export const getWorkspaceCommandReceipt = <T, >(operationId: string, options?: AiRequestOptions) => result<CommandReceipt<T>>('getWorkspaceCommandReceipt', {operationId}, options);
export const checkWorkspaceAccess = (scope: {
  tenantId: string;
  workspaceId?: string
}, resourceType: 'tenant' | 'workspace', action: string, options?: AiRequestOptions) => result<{
  allowed: boolean;
  reason: string;
  checkedAt: string
}>('access/checkWorkspaceAccess', {scope, resourceType, action}, options);
