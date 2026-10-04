import {beforeEach, describe, expect, it} from 'vitest';
import {AiNewApiError, normalizeApiError} from '@/services/ai-new/request';
import type {ExecutionError} from '@/types/ai-new/conversation';
import {chatErrorText, executionErrorText} from './errors';

const facts = (code: string, failureStage: string): ExecutionError => ({
  code: `arte.common.${code}`,
  failureStage,
  retryable: false,
  sideEffectStatus: 'NONE',
  resultCertainty: 'CONFIRMED',
});
beforeEach(() => {
  localStorage.setItem('umi_locale', 'zh-CN');
});
describe('execution failure guidance', () => {
  it('distinguishes missing article read policy and article authorization from an invalid session', () => {
    expect(chatErrorText(new AiNewApiError(403, facts('unauthorized', 'application-policy-read')))).toContain('文章读取许可');
    expect(chatErrorText(new AiNewApiError(403, facts('unauthorized', 'application-policy')))).toContain('操作许可');
    expect(chatErrorText(new AiNewApiError(403, facts('unauthorized', 'rag-article')))).toContain('该文章的授权');
    expect(chatErrorText(new AiNewApiError(403, facts('unauthorized', 'identity')))).toContain('登录状态');
    expect(chatErrorText(new AiNewApiError(404, facts('not_found', 'rag-article')))).toContain('不存在');
  });
  it('distinguishes budget exhaustion, missing budget policy and execution rate limits', () => {
    expect(executionErrorText(facts('rate_limited', 'budget'))).toContain(
      '可用预算不足',
    );
    expect(executionErrorText(facts('policy_unavailable', 'budget'))).toContain(
      '预算未配置',
    );
    expect(executionErrorText(facts('rate_limited', 'admission'))).toContain(
      '执行容量',
    );
    expect(
      executionErrorText(facts('idempotency_conflict', 'chat-store')),
    ).toContain('不要更换幂等键');
  });
  it('prioritizes uncertainty over a misleading rejection classification', () => {
    const unknown: ExecutionError = {
      ...facts('rate_limited', 'budget'),
      sideEffectStatus: 'UNKNOWN',
      resultCertainty: 'UNKNOWN',
    };
    expect(chatErrorText(new AiNewApiError(503, unknown))).toContain(
      '无法确认操作结果',
    );
    expect(chatErrorText(new AiNewApiError(503, unknown))).not.toContain(
      '未产生副作用',
    );
  });
  it('gives actionable stable guidance without reflecting transport or provider messages', () => {
    expect(
      chatErrorText(normalizeApiError({message: 'secret provider text'})),
    ).toContain('连接中断');
    expect(
      chatErrorText(normalizeApiError({message: 'secret provider text'})),
    ).not.toContain('secret');
    expect(
      chatErrorText(normalizeApiError({code: 'ECONNABORTED'})),
    ).toContain('等待超时');
    expect(executionErrorText(facts('deadline_exceeded', 'prepare'))).toContain(
      '期限已过',
    );
    expect(executionErrorText(facts('unsupported', 'model'))).toContain(
      '不支持',
    );
    expect(executionErrorText(facts('internal_error', 'prepare'))).toContain(
      '未产生副作用',
    );
  });
});
