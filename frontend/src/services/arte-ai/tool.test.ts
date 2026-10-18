import {beforeEach, describe, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {bindingRequest, listTools, writeToolBinding} from './tool';
import {parseConfiguration} from './toolJson';
import {scope, settings} from '@/features/aitool/fixtures.testdata';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
beforeEach(() => vi.mocked(request).mockReset());
describe('TOOL 请求沿用统一传输', () => {
  it('写入发送原始 JSON 和原幂等键，不进行 double 往返', async () => {
    vi.mocked(request).mockResolvedValue({
      status: 200,
      data: {success: true, code: '000000', desc: '成功', data: {delivery: 'APPLIED'}}
    });
    const body = bindingRequest(scope, {expectedVersion: '0', reason: 'test', settings});
    await writeToolBinding('saveBinding', body, 'same-key');
    const call = vi.mocked(request).mock.calls[0] as unknown as [string, {
      data: unknown;
      headers: Record<string, string>
    }];
    expect(call[0]).toBe('/arte/ai-new/tool/saveBinding');
    expect(call[1]?.data).toBe(body);
    expect(call[1]?.headers).toMatchObject({'Idempotency-Key': 'same-key'});
    expect(body).toContain('0.123456789012345678901');
  });
  it('回读对象的旧 configuration 和未知字段不进入新写请求，不产生重复键', () => {
    const runtime = {...settings, configuration: {decimal: 0.123}, owner: 'not-a-request-field'};
    const body = bindingRequest(scope, {expectedVersion: '1', reason: 'save', settings: runtime});
    expect(() => parseConfiguration(body)).not.toThrow();
    expect(body.match(/"configuration":/g)).toHaveLength(1);
    expect(body).not.toContain('not-a-request-field');
    expect(body).toContain('0.123456789012345678901');
  });
  it('目录传递服务器筛选与游标，保留业务错误', async () => {
    vi.mocked(request).mockResolvedValueOnce({
      status: 200,
      data: {success: true, code: '000000', desc: '成功', data: {records: [], nextCursor: 'cursor', exhausted: false}}
    });
    await listTools(scope, 'original', undefined, {query: '统计', providerId: 'local'});
    expect((vi.mocked(request).mock.calls[0] as unknown as [string, {
      data: unknown
    }])[1].data).toMatchObject({cursor: 'original', query: '统计', providerId: 'local'});
    vi.mocked(request).mockResolvedValue({status: 403, data: {success: false, code: '205002', desc: '无权'}});
    await expect(listTools(scope, null)).rejects.toMatchObject({httpStatus: 403, code: '205002'});
  });
});
