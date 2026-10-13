import {type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import type {CommandReceipt, WorkspaceScope} from './workspaceTypes';

export interface DefinitionRef {
  type: string;
  id: string;
  version: string
}

export interface Money {
  amount: string | number;
  currency: string
}

export interface BudgetPreset {
  rate: DefinitionRef;
  limit: Money
}

export interface AiPreset {
  enabled: boolean;
  bindings: DefinitionRef[];
  budgets: BudgetPreset[]
}

export interface AiModel {
  binding: DefinitionRef;
  name: string;
  connection: DefinitionRef;
  rate: DefinitionRef
}

export interface WorkspaceAiView {
  mode: 'DATABASE';
  configuration: { scope: WorkspaceScope; preset: AiPreset; templateVersion: number; version: number } | null;
  workspaceVersion: number;
  canManage: boolean;
  needsImport: boolean;
  models: AiModel[];
  defaults: AiPreset | null;
}

export interface PublishedBinding {
  definition: DefinitionRef;
  capability: { definition: DefinitionRef; [key: string]: unknown };
  connection: DefinitionRef;
  remoteOperation: string;
  contextWindowTokens: number;
  rate: DefinitionRef;
}

export interface AiCatalog {
  capabilities: { definition: DefinitionRef; [key: string]: unknown }[];
  bindings: PublishedBinding[];
  connections: { definition: DefinitionRef; providerId: string; endpoint: string; [key: string]: unknown }[];
  rates: { definition: DefinitionRef; inputPerMillion: Money; outputPerMillion: Money }[];
  legacyGrants: unknown[];
  legacyBudgets: unknown[];
  defaults: AiPreset;
}

export interface CatalogView {
  version: number;
  catalog: AiCatalog | null
}

const base = '/arte/workspace/ai';
export const ensureWorkspaceAi = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceAiView>('ensure', {scope}, options, undefined, base);
export const getWorkspaceAi = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceAiView>('getConfiguration', {scope}, options, undefined, base);
export const updateWorkspaceAi = (request: {
  scope: WorkspaceScope;
  expectedWorkspaceVersion: number;
  expectedVersion: number;
  preset: AiPreset
}, key: string, options?: AiRequestOptions) => {
  requireIdempotencyKey(key);
  return postResult<CommandReceipt<WorkspaceAiView>>('updateConfiguration', request, options, key, base);
};
export const getAiCatalog = (options?: AiRequestOptions) => postResult<CatalogView>('getCatalog', {}, options, undefined, base);
export const importAiConfiguration = (key: string) => {
  requireIdempotencyKey(key);
  return postResult<CommandReceipt<CatalogView>>('importConfiguration', {}, undefined, key, base);
};
export const publishAiCatalog = (request: { expectedVersion: number; catalog: AiCatalog }, key: string) => {
  requireIdempotencyKey(key);
  return postResult<CommandReceipt<CatalogView>>('publishCatalog', request, undefined, key, base);
};
export const refKey = (ref: DefinitionRef) => JSON.stringify([ref.type, ref.id, ref.version]);
