import {z} from 'zod';
import {type AiRequestOptions, post, postResult, requireIdempotencyKey} from './request';
import type {AiResult, AiScope, DefinitionRef} from './types';

const id = z.string().min(1).max(256);
const scope = z.object({tenantId: id, workspaceId: id});
const ref = z.object({type: id, id, version: id});
const revision = z.number().int().positive();
const resourceVersion = z.number().int().nonnegative();
const content = z.object({
  displayName: z.string().max(160),
  description: z.string().max(1000),
  systemInstruction: z.string().max(8192),
  expectedOutput: z.string().max(2000),
  examplePrompts: z.array(z.string().max(1000)).max(5),
});
export const assistantCommandSchema = z.discriminatedUnion('kind', [
  z.object({kind: z.literal('create'), scope, content}),
  z.object({
    kind: z.literal('copy'),
    scope,
    sourceId: id,
    sourceVersion: revision.optional(),
    sourceDraftRevision: revision.optional()
  }),
  z.object({kind: z.literal('save'), scope, assistantId: id, expectedRevision: revision, content}),
  z.object({
    kind: z.literal('publish'),
    scope,
    assistantId: id,
    expectedVersion: resourceVersion,
    expectedRevision: revision,
    trialId: id
  }),
  z.object({kind: z.literal('disable'), scope, assistantId: id, expectedVersion: resourceVersion}),
  z.object({
    kind: z.literal('trial'), scope, assistantId: id, draftRevision: revision, text: z.string().min(1).max(1_000_000),
    capability: ref, binding: ref, budgetRef: id, maxInputTokens: revision, timeoutSeconds: revision,
    generationOptions: z.object({
      maxOutputTokens: revision,
      temperature: z.number().nullish(),
      topP: z.number().nullish(),
      stopSequences: z.array(z.string()),
    }),
  }),
]);

export type AssistantContent = z.infer<typeof content>;
export type AssistantCommand = z.infer<typeof assistantCommandSchema>;
export type AssistantTrialCommand = Extract<AssistantCommand, { kind: 'trial' }>;
export type AssistantState = 'DRAFT' | 'ACTIVE' | 'DISABLED';

export interface AssistantSummary {
  assistantId: string;
  displayName: string;
  description: string;
  examplePrompts: string[];
  chatProfile: DefinitionRef<'chat-profile'>;
}

interface AssistantResource {
  id: string;
  state: AssistantState;
  releaseVersion: number | null;
  version: number;
}

export interface ManagedAssistant {
  resource: AssistantResource;
  displayName: string;
  draftRevision: number;
}

export interface AssistantDetail {
  resource: AssistantResource;
  publishedDraftRevision: number | null;
  draft: { revision: number; content: AssistantContent; updatedAt: string; updatedBy: string };
}

export interface AssistantCapabilities {
  canCreate: boolean;
  canEdit: boolean;
  canTrial: boolean;
  canPublish: boolean;
  canDisable: boolean;
}

export interface AssistantRelease {
  version: number;
  draftRevision: number;
  content: AssistantContent;
  publishedAt: string;
  publishedBy: string;
}

export interface AssistantTrial {
  trialId: string;
  draftRevision: number;
  invocationId: string;
  createdAt: string;
}

export interface AssistantPage<T> {
  items: T[];
  nextCursor: string | null;
}

interface AssistantTarget {
  scope: AiScope;
  assistantId: string
}

interface DirectoryRequest {
  scope: AiScope;
  cursor: string | null;
  limit: number;
  name?: string
}

const receipt = z.object({assistantId: id, draftRevision: revision, resourceVersion});
const acceptedTrial = z.object({
  trialId: id, draftRevision: revision, execution: z.object({executionId: id, acceptedAt: z.string().min(1)}),
});
type Receipt = z.infer<typeof receipt>;
type AcceptedTrial = z.infer<typeof acceptedTrial>;

const operations = {
  create: 'createAssistant', copy: 'copyAssistant', save: 'saveAssistantDraft',
  publish: 'publishAssistant', disable: 'disableAssistant', trial: 'trialAssistantDraft',
};

async function read<T>(path: string, data: unknown, options: AiRequestOptions) {
  return (await postResult<T>('assistant/' + path, data, options)).body.data;
}

export const listUsableAssistants = (data: DirectoryRequest, options: AiRequestOptions) =>
  read<AssistantPage<AssistantSummary>>('listUsableAssistants', data, options);
export const listManagedAssistants = (data: DirectoryRequest, options: AiRequestOptions) =>
  read<AssistantPage<ManagedAssistant>>('listManagedAssistants', data, options);
export const getAssistant = (data: AssistantTarget, options: AiRequestOptions) =>
  read<AssistantDetail>('getAssistant', data, options);
export const getAssistantCapabilities = (data: { scope: AiScope; assistantId?: string }, options: AiRequestOptions) =>
  read<AssistantCapabilities>('getAssistantCapabilities', data, options);
export const listAssistantVersions = (data: AssistantTarget & DirectoryRequest, options: AiRequestOptions) =>
  read<AssistantPage<AssistantRelease>>('listAssistantVersions', data, options);
export const listAssistantTrials = (data: AssistantTarget & DirectoryRequest, options: AiRequestOptions) =>
  read<AssistantPage<AssistantTrial>>('listAssistantTrials', data, options);

export async function getConversationAssistant(data: {
  scope: AiScope;
  conversationId: string
}, options: AiRequestOptions) {
  return (await post<AiResult<{
    summary: AssistantSummary;
    available: boolean;
    disabled: boolean
  } | null>>('assistant/getConversationAssistant', data, options)).body.data;
}

type AssistantCommandResult = { kind: 'trial'; trial: AcceptedTrial }
  | { kind: Exclude<AssistantCommand['kind'], 'trial'>; receipt: Receipt };

export async function executeAssistantCommand(command: AssistantCommand, key: string, options: AiRequestOptions): Promise<AssistantCommandResult> {
  const {kind, ...data} = command;
  const path = 'assistant/' + operations[kind];
  const idempotencyKey = requireIdempotencyKey(key);
  if (command.kind === 'trial') {
    const {httpStatus, body} = await postResult<AcceptedTrial>(path, data, options, idempotencyKey);
    const trial = acceptedTrial.parse(body.data);
    if (httpStatus !== 202 || trial.draftRevision !== command.draftRevision) throw new Error('Invalid assistant trial receipt');
    return {kind: command.kind, trial};
  }
  const {body} = await postResult<{ value: Receipt }>(path, data, options, idempotencyKey);
  const value = receipt.parse(body.data.value);
  if ('assistantId' in command && value.assistantId !== command.assistantId) throw new Error('Invalid assistant command receipt');
  return {kind: command.kind, receipt: value};
}
