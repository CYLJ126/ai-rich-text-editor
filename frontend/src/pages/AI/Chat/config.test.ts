import {beforeEach, describe, expect, it, vi} from 'vitest';
import {
  CHAT_CONFIG_STORAGE_KEY,
  chatConfigSchema,
  clearChatConfig,
  DEFAULT_CHAT_CONFIG,
  loadChatConfig,
  saveChatConfig,
} from './config';

const valid = {
  ...DEFAULT_CHAT_CONFIG,
  tenantId: 'tenant-1',
  workspaceId: 'workspace-1',
  capabilityId: 'text-generation',
  capabilityVersion: 'v1',
  bindingId: 'default-binding',
  bindingVersion: 'v2',
  budgetRef: 'budget-1',
};

beforeEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('测试配置校验', () => {
  it('不假设后端存在默认引用，初始配置不能直接应用', () => {
    expect(chatConfigSchema.safeParse(DEFAULT_CHAT_CONFIG).success).toBe(false);
  });

  it('规范化引用空白并将省略的采样参数保留为 null', () => {
    const parsed = chatConfigSchema.parse({
      ...valid,
      tenantId: ' tenant-1 ',
      temperature: undefined,
      topP: undefined,
    });
    expect(parsed.tenantId).toBe('tenant-1');
    expect(parsed.temperature).toBeNull();
    expect(parsed.topP).toBeNull();
  });

  it.each([
    'tenantId',
    'workspaceId',
    'capabilityId',
    'bindingId',
    'budgetRef',
  ])('拒绝空白引用 %s', (field) => {
    expect(
      chatConfigSchema.safeParse({...valid, [field]: '   '}).success,
    ).toBe(false);
  });

  it.each(['latest', 'LATEST', ' latest '])('拒绝非固定版本 %s', (version) => {
    for (const field of ['capabilityVersion', 'bindingVersion']) {
      expect(
        chatConfigSchema.safeParse({...valid, [field]: version}).success,
      ).toBe(false);
    }
  });

  it('拒绝超长引用以及以字符串假冒的数值', () => {
    expect(
      chatConfigSchema.safeParse({...valid, budgetRef: 'a'.repeat(257)})
        .success,
    ).toBe(false);
    expect(
      chatConfigSchema.safeParse({...valid, maxInputTokens: '4096'}).success,
    ).toBe(false);
  });

  it.each([
    ['maxInputTokens', 0],
    ['maxInputTokens', 10_000_001],
    ['maxInputTokens', 1.5],
    ['maxOutputTokens', 0],
    ['maxOutputTokens', 1_000_001],
    ['maxOutputTokens', 1.5],
    ['timeoutSeconds', 0],
    ['timeoutSeconds', 3601],
    ['timeoutSeconds', 1.5],
    ['temperature', -0.1],
    ['temperature', 2.1],
    ['topP', 0],
    ['topP', 1.1],
    ['maxInputTokens', Number.NaN],
    ['temperature', Number.POSITIVE_INFINITY],
  ])('拒绝 %s=%s', (field, value) => {
    expect(
      chatConfigSchema.safeParse({...valid, [field]: value}).success,
    ).toBe(false);
  });

  it('允许契约边界和明确的 Temperature=0', () => {
    const result = chatConfigSchema.parse({
      ...valid,
      maxInputTokens: 10_000_000,
      maxOutputTokens: 1_000_000,
      timeoutSeconds: 3600,
      temperature: 0,
      topP: 1,
    });
    expect(result.temperature).toBe(0);
    expect(result.topP).toBe(1);
  });
});

describe('配置保存与恢复', () => {
  it('恢复已应用的规范化配置，清除时只删除此配置', () => {
    expect(loadChatConfig()).toEqual({config: null, warning: null});
    localStorage.setItem('user_token', 'test-token');
    const config = chatConfigSchema.parse(valid);
    expect(saveChatConfig(config)).toBe(true);
    expect(loadChatConfig()).toEqual({config, warning: null});
    expect(clearChatConfig()).toBe(true);
    expect(loadChatConfig().config).toBeNull();
    expect(localStorage.getItem('user_token')).toBe('test-token');
  });

  it.each([
    'bad json',
    'null',
    '{}',
    '{"version":2,"config":{}}',
    '{"version":1,"config":{}}',
  ])('失效缓存不会当作可用配置 %#', (raw) => {
    localStorage.setItem(CHAT_CONFIG_STORAGE_KEY, raw);
    expect(loadChatConfig()).toEqual({config: null, warning: 'invalid'});
  });

  it('丢弃未知字段，缓存不保存 Token 或供应商密钥', () => {
    const config = chatConfigSchema.parse(valid);
    expect(
      saveChatConfig({
        ...config,
        token: 'secret',
        apiKey: 'secret',
      } as typeof config),
    ).toBe(true);
    const stored = localStorage.getItem(CHAT_CONFIG_STORAGE_KEY);
    expect(stored).not.toContain('secret');
    expect(stored).not.toContain('apiKey');
  });

  it('禁止浏览器存储时返回明确结果', () => {
    vi.spyOn(localStorage, 'getItem').mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    expect(loadChatConfig()).toEqual({config: null, warning: 'unavailable'});
    vi.spyOn(localStorage, 'setItem').mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    expect(saveChatConfig(chatConfigSchema.parse(valid))).toBe(false);
    vi.spyOn(localStorage, 'removeItem').mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    expect(clearChatConfig()).toBe(false);
  });
});
