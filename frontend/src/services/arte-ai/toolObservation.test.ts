import {beforeEach, expect, it, vi} from 'vitest';
import {postResult} from './request';
import {getToolStatistics, listToolExecutions} from './toolObservation';

vi.mock('./request', () => ({postResult: vi.fn()}));
beforeEach(() => vi.resetAllMocks());
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const query = {from: '2026-10-21T00:00:00.000Z', until: '2026-10-22T00:00:00.000Z'};

it('uses bounded paging with the exact committed window and cursor', () => {
  listToolExecutions(scope, {...query, toolId: 'text', state: 'UNKNOWN'}, 'cursor');
  expect(postResult).toHaveBeenCalledWith('tool/listExecutions', {
    scope, ...query,
    toolId: 'text',
    state: 'UNKNOWN',
    cursor: 'cursor',
    limit: 10
  }, undefined);
});
it('does not send a page limit, subject override or execution intent to statistics', () => {
  getToolStatistics(scope, query);
  expect(postResult).toHaveBeenCalledWith('tool/getStatistics', {scope, ...query}, undefined);
});
