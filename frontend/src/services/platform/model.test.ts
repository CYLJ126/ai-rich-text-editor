import {beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import * as api from './model';
import {readModelCommand, saveModelCommand} from '@/features/ainew/modelManagementState';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const model = {
  resourceId: 'model',
  scope,
  name: '私有模型',
  createdBy: 'other',
  state: 'DRAFT',
  bindingId: null,
  version: 0,
  createdAt: 'now'
};
const answer = (data: unknown) => ({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
const receipt = (value: unknown) => ({
  operationId: 'op',
  status: 1,
  delivery: 0,
  value,
  resultVersion: 1,
  acceptedAt: 'now',
  completedAt: 'now'
});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  localStorage.clear();
});

it('uses the current scope and opaque cursor, preserving empty readable pages with continuation', async () => {
  vi.mocked(request).mockResolvedValue(answer({records: [], nextCursor: 'next'}));
  expect((await api.listManagedModels(scope)).body.data).toEqual({records: [], nextCursor: 'next'});
  expect(request).toHaveBeenCalledWith('/arte/ai-new/model-draft/listModelResources', expect.objectContaining({
    data: {
      scope,
      cursor: null,
      size: 20
    }, method: 'POST'
  }));
});
it('rejects cross-scope resources and duplicate identities', async () => {
  vi.mocked(request).mockResolvedValue(answer({
    records: [{...model, scope: {...scope, workspaceId: 'other'}}],
    nextCursor: null
  }));
  await expect(api.listManagedModels(scope)).rejects.toThrow();
  vi.mocked(request).mockResolvedValue(answer({records: [model, model], nextCursor: null}));
  await expect(api.listManagedModels(scope)).rejects.toThrow();
});
it('keeps safe draft/credential fields only and rejects identity substitutions', async () => {
  vi.mocked(request).mockResolvedValue(answer({
    draft: null,
    credential: {
      resourceId: 'model',
      state: 'MISSING',
      version: 0,
      mayMaintain: false,
      token: 'must-not-escape',
      ciphertext: 'hidden'
    }
  }));
  expect(JSON.stringify((await api.getModelDraft(scope, 'model')).body.data)).not.toMatch(/must-not-escape|ciphertext|hidden/);
  await expect(api.getModelDraft(scope, 'other')).rejects.toThrow();
});
it('sends a private credential only in its dedicated request and keeps the caller key', async () => {
  const command = {
    kind: 'credential',
    key: 'same-key',
    request: {scope, resourceId: 'model', expectedVersion: 2}
  } as const;
  vi.mocked(request).mockResolvedValue(answer(receipt({
    resourceId: 'model',
    state: 'CONFIGURED',
    version: 3,
    mayMaintain: true
  })));
  await api.executeModelCommand(command, 'private-fixture-key');
  expect(request).toHaveBeenCalledWith('/arte/ai-new/model-draft/replaceModelCredential', expect.objectContaining({
    data: {...command.request, token: 'private-fixture-key'},
    headers: expect.objectContaining({'Idempotency-Key': 'same-key'}),
  }));
  expect(saveModelCommand(context, command)).toBe(true);
  expect(sessionStorage.getItem([...Object.keys(sessionStorage)][0]) ?? JSON.stringify(sessionStorage)).not.toContain('private-fixture-key');
  expect(readModelCommand(context).command).toEqual(command);
  expect(saveModelCommand(context, {
    ...command,
    request: {...command.request, token: 'private-fixture-key'}
  } as never)).toBe(false);
});
it('refuses missing or malformed credential input without transport', async () => {
  const command = {
    kind: 'credential',
    key: 'same-key',
    request: {scope, resourceId: 'model', expectedVersion: 2}
  } as const;
  await expect(api.executeModelCommand(command)).rejects.toThrow();
  await expect(api.executeModelCommand(command, 'invalid key')).rejects.toThrow();
  expect(request).not.toHaveBeenCalled();
});
it('uses ordinary creation without platform catalog endpoints or client-owned identities', async () => {
  vi.mocked(request).mockResolvedValue(answer(receipt(model)));
  await api.executeModelCommand({kind: 'create', key: 'create-key', request: {scope, name: '私有模型'}});
  expect(request).toHaveBeenCalledWith('/arte/ai-new/model-resource/createModelResource', expect.objectContaining({
    data: {
      scope,
      name: '私有模型'
    }
  }));
});
it('requires each permission decision to match its requested target and order', async () => {
  const checks = api.MODEL_ACTIONS.map(action => ({
    target: {scope, resourceType: 'model', resourceId: 'model'},
    action,
    allowed: true,
    reason: 'ALLOWED',
    checkedAt: 'now'
  }));
  vi.mocked(request).mockResolvedValue(answer(checks));
  expect((await api.checkModelActions(scope, 'model')).has('model:publish')).toBe(true);
  vi.mocked(request).mockResolvedValue(answer([...checks].reverse()));
  await expect(api.checkModelActions(scope, 'model')).rejects.toThrow();
});
it('queries the original validation identity and compares publication references by fields', async () => {
  vi.mocked(request).mockResolvedValue(answer({
    validationId: 'other-validation', resourceId: 'model', draftVersion: 1, credentialVersion: 1,
    state: 'SUCCEEDED', code: 'MODEL_FOUND', expiresAt: 'now', checkedAt: 'now'
  }));
  await expect(api.getModelValidation(scope, 'original-validation')).rejects.toThrow();
  const binding = {version: 'v1', id: 'personal-binding', type: 'binding'};
  vi.mocked(request).mockResolvedValue(answer({
    binding,
    ready: false,
    reason: 'NO_COMPATIBLE_BUDGET',
    budgetRefs: [],
    maxOutputTokens: 512
  }));
  expect((await api.getPublicationStatus(scope, 'model', binding)).body.data.reason).toBe('NO_COMPATIBLE_BUDGET');
  await expect(api.getPublicationStatus(scope, 'model', {...binding, version: 'v2'})).rejects.toThrow();
});
it('isolates command recovery by user and scope and blocks corrupted records', () => {
  const command = {kind: 'create', key: 'same-key', request: {scope, name: '私有模型'}} as const;
  saveModelCommand(context, command);
  expect(readModelCommand({...context, userId: 'other'}).command).toBeNull();
  expect(readModelCommand({...context, scope: {...scope, workspaceId: 'other'}}).command).toBeNull();
  const key = sessionStorage.key(0)!;
  sessionStorage.setItem(key, '{bad');
  expect(readModelCommand(context).blocked).toBe(true);
});
