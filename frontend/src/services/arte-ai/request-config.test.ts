import {message} from 'antd';
import type {AxiosRequestConfig, AxiosResponse} from 'axios';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {errorConfig} from '@/requestErrorConfig';

vi.mock('@umijs/max', () => ({getLocale: () => 'zh-CN', getIntl: vi.fn()}));
vi.mock('@/utils/i18n', () => ({i18nText: () => '业务错误'}));
vi.mock('antd', () => ({
  message: {error: vi.fn().mockResolvedValue(undefined)},
  notification: {open: vi.fn()},
}));

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('复用全局请求配置', () => {
  const requestInterceptor = errorConfig.requestInterceptors?.[0];
  const responseInterceptor = errorConfig.responseInterceptors?.[0];

  it.each([undefined, 'test-token'])(
    'Token=%s 时保留 JSON/幂等请求头并增加当前语言',
    async (token) => {
      if (token) localStorage.setItem('user_token', token);
      if (typeof requestInterceptor !== 'function')
        throw new Error('Missing request interceptor');
      const intercept = requestInterceptor as (
        config: Record<string, unknown>,
      ) => Record<string, unknown>;
      const config = await intercept({
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'saved-key',
        },
      });
      expect(config.headers).toEqual({
        'Content-Type': 'application/json',
        'Idempotency-Key': 'saved-key',
        'Accept-Language': 'zh-CN',
        ...(token ? {Authorization: 'Bearer test-token'} : {}),
      });
    },
  );

  it.each([false, true])(
    'skipErrorHandler=%s 控制全局业务错误弹窗',
    async (skipErrorHandler) => {
      if (typeof responseInterceptor !== 'function')
        throw new Error('Missing response interceptor');
      const response: AxiosResponse = {
        data: {success: false, code: '205041', desc: '结果尚未可用'},
        status: 409,
        statusText: 'Conflict',
        headers: {},
        config: {skipErrorHandler} as unknown as AxiosRequestConfig,
      };
      expect(await responseInterceptor(response)).toBe(response);
      expect(message.error).toHaveBeenCalledTimes(skipErrorHandler ? 0 : 1);
    },
  );
});
