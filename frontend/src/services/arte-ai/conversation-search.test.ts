import {request} from '@umijs/max';
import {beforeEach, expect, it, vi} from 'vitest';
import {closeSearch, queryTurnsAround, searchConversations} from './conversation-search';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const metadata = {success: true, code: '000000', desc: 'OK'};
beforeEach(() => vi.mocked(request).mockReset());

it('accepts a successful close with null data and still rejects permission errors', async () => {
  vi.mocked(request).mockResolvedValue({status: 200, data: {...metadata, data: null}});
  expect((await closeSearch({scope, searchSessionId: 'session'})).body.success).toBe(true);
  vi.mocked(request).mockResolvedValue({status: 403, data: {...metadata, success: false}});
  await expect(closeSearch({scope, searchSessionId: 'session'})).rejects.toMatchObject({httpStatus: 403});
});

it('keeps repeated new searches separate and forwards signed cursors and cancellation', async () => {
  const page = {
    searchSessionId: 'session',
    records: [],
    nextCursor: 'signed',
    firstCursor: 'first',
    previousCursor: null,
    total: null,
    partialPage: true,
    endReason: null
  };
  vi.mocked(request).mockResolvedValue({status: 200, data: {...metadata, data: page}});
  const controller = new AbortController();
  const query = {scope, query: '关键词', size: 20};
  await Promise.all([searchConversations(query), searchConversations(query)]);
  expect(request).toHaveBeenCalledTimes(2);
  await searchConversations({...query, cursor: 'signed'}, {signal: controller.signal});
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/conversation/searchConversations', expect.objectContaining({
    data: {
      ...query,
      cursor: 'signed'
    }, signal: controller.signal
  }));
});

it('reads history independently and preserves structured HTTP failures', async () => {
  const history = {scope, conversationId: 'conversation', size: 5, historyCursor: 'signed-history'};
  vi.mocked(request).mockResolvedValue({status: 409, data: {...metadata, success: false, code: '205030'}});
  await expect(queryTurnsAround(history)).rejects.toMatchObject({httpStatus: 409, code: '205030'});
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/conversation/queryTurnsAround', expect.objectContaining({data: history}));
});
