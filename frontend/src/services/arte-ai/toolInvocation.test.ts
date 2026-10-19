import {describe, expect, it} from 'vitest';
import {confirmedToolRequest, toolInvocationRequest} from './toolInvocation';

const scope = {tenantId: 'tenant', workspaceId: 'space'};
describe('调用请求的精确参数与白名单', () => {
  it('保留大整数和小数原文，支持合法的参数 token 字段', () => {
    const args = '{"large":9007199254740993,"decimal":0.1234567890123456789,"token":"business term"}';
    const body = toolInvocationRequest(scope, 'binding', args, 30);
    expect(body).toContain(args);
    expect(Object.keys(JSON.parse(body))).toEqual(['scope', 'input']);
    expect(confirmedToolRequest(body, 'a'.repeat(64))).toContain('"expectedPlanDigest"');
  });
  it('拒绝重复键、无效摘要及期限', () => {
    expect(() => toolInvocationRequest(scope, 'binding', '{"x":1,"x":2}', 30)).toThrow();
    expect(() => toolInvocationRequest(scope, 'binding', '{}', 0)).toThrow();
    expect(() => confirmedToolRequest('{}', 'invalid')).toThrow();
  });
});
