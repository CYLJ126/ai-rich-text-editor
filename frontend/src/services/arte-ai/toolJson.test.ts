import {describe, expect, it, vi} from 'vitest';
import {encodeJson, parseConfiguration} from './toolJson';
import {bindingRequest, defaultBindingRequest} from '@/services/arte-ai/tool';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const tool = {type: 'tool', id: 'read', version: 'v1'};
describe('工具配置精确数字', () => {
  it('编辑其他字段不改变数字、null、数组', () => {
    const tree = parseConfiguration('{"decimal":0.123456789012345678901,"large":9007199254740993,"null":null,"array":[1e-99,"\\u4e2d"]}');
    tree.fields.set('name', {kind: 'string', value: 'new'});
    expect(encodeJson(tree)).toContain('0.123456789012345678901');
    expect(encodeJson(tree)).toContain('9007199254740993');
    expect(encodeJson(tree)).toContain('"null":null');
    expect(encodeJson(tree)).toContain('[1e-99,"中"]');
  });
  it.each(['{"a":1,"a":2}', '{"a":01}', '{"a":1,}', '{"a":NaN}', '{"a":"\\q"}', '[]', '{"a":1} junk'])('拒绝无效或含糊 JSON %s', source => expect(() => parseConfiguration(source)).toThrow());
  it('限制深度、字节、元素和凭据字段', () => {
    expect(() => parseConfiguration(`${'{"a":'.repeat(26)}0${'}'.repeat(26)}`)).toThrow();
    expect(() => parseConfiguration(JSON.stringify({long: '中'.repeat(400000)}))).toThrow();
    expect(() => parseConfiguration(`{"a":[${Array(257).fill('0').join(',')}]}`)).toThrow();
    expect(() => parseConfiguration('{"nested":{"api_key":"never-store"}}')).toThrow(/凭据/);
    expect(encodeJson(parseConfiguration('{"__proto__":{"safe":true}}'))).toBe('{"__proto__":{"safe":true}}');
  });
  it('请求嵌入配置原文，其他字段安全转义，版本保持字符串', () => {
    const request = bindingRequest(scope, {
      bindingId: 'id', expectedVersion: '9007199254740993', reason: 'quote"', settings: {
        tool,
        name: '"configuration":null',
        enabled: true,
        versionPolicy: 'PINNED',
        configurationJson: '{"decimal":0.1234567890123456789}',
        secrets: {},
        policy: null,
        networkOrigins: null,
      }
    });
    expect(request).toContain('"decimal":0.1234567890123456789');
    expect(JSON.parse(request).expectedVersion).toBe('9007199254740993');
    expect(JSON.parse(request).settings.name).toBe('"configuration":null');
    expect(defaultBindingRequest(scope, tool, '{}')).toContain('"configuration":{}');
  });
});
