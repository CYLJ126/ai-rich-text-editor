import {beforeEach, expect, it, vi} from 'vitest';
import {AiApiError, postResult} from '@/services/arte-ai/request';
import {type ConnectionCommand, executeConnectionCommand, getConnections} from './connection';

vi.mock('@/services/arte-ai/request', async original => ({
  ...(await original<typeof import('@/services/arte-ai/request')>()), postResult: vi.fn(),
}));
vi.mock('@umijs/max', () => ({request: vi.fn()}));
const view = {version: 1, initialized: true, connections: [], allowedOrigins: ['https://api.deepseek.com']};
const command: ConnectionCommand = {
  kind: 'publish', request: {
    expectedVersion: 0, settings: {
      id: 'deepseek', providerId: 'deepseek', endpoint: 'https://api.deepseek.com/v1',
      connectTimeoutSeconds: 5, responseTimeoutSeconds: 120, maxResponseBytes: 1024,
    }
  }
};
const reply = (data: unknown) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
beforeEach(() => vi.resetAllMocks());

it('submits only declared fields and the original idempotency key', async () => {
  vi.mocked(postResult).mockResolvedValue(reply({
    operationId: 'operation', status: 1, delivery: 0, value: view,
    resultVersion: 1, acceptedAt: 'now', completedAt: 'now'
  }));
  expect(await executeConnectionCommand(command, 'original-key')).toEqual(view);
  expect(postResult).toHaveBeenCalledWith('publishConnection', command.request, undefined, 'original-key', '/arte/workspace/ai');
});
it('rejects unexpected credentials and invalid addresses before transport', async () => {
  const unsafe = {...command, request: {...command.request, settings: {...command.request.settings, token: 'secret'}}};
  await expect(executeConnectionCommand(unsafe as ConnectionCommand, 'original-key')).rejects.toThrow();
  await expect(executeConnectionCommand({
    ...command, request: {
      ...command.request, settings: {
        ...command.request.settings,
        endpoint: 'https://name:secret@example.com/v1'
      }
    }
  }, 'original-key')).rejects.toThrow();
  expect(postResult).not.toHaveBeenCalled();
});
it('treats a malformed success response as uncertain', async () => {
  vi.mocked(postResult).mockResolvedValue(reply({version: 1, initialized: false, connections: [], allowedOrigins: []}));
  await expect(getConnections()).rejects.toMatchObject({httpStatus: 200, code: 'INVALID_CONNECTION_RESPONSE'});
});
it('preserves HTTP denial instead of converting it into an empty catalog', async () => {
  const denied = new AiApiError(403, {});
  vi.mocked(postResult).mockRejectedValue(denied);
  await expect(getConnections()).rejects.toBe(denied);
});
