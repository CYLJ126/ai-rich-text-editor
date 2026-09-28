import {describe, expect, it} from 'vitest';
import type {ToolBindingView} from '@/types/ai.tool.type';
import {compactPolicyOverride, containsSensitiveConfigurationKey, validateAssistantToolCommands,} from './binding';

const binding = (bindingId: string, version: string): ToolBindingView => ({
  bindingId,
  tool: {namespace: 'article', name: 'summary', version},
  configuration: {},
  enabled: true,
  available: true,
  rowVersion: 0,
});

describe('AI tool binding helpers', () => {
  it('detects sensitive keys recursively', () => {
    expect(
      containsSensitiveConfigurationKey({nested: {api_key: 'secret'}}),
    ).toBe(true);
    expect(
      containsSensitiveConfigurationKey({endpoint: 'https://example.test'}),
    ).toBe(false);
  });

  it('removes inherited policy fields', () => {
    expect(
      compactPolicyOverride({
        timeout: '',
        maxRetries: 0,
        requiresApproval: true,
      }),
    ).toEqual({
      maxRetries: 0,
      requiresApproval: true,
    });
    expect(compactPolicyOverride({})).toBeUndefined();
  });

  it('rejects multiple enabled versions with the same model tool name', () => {
    const bindings = [binding('one', '1.0.0'), binding('two', '2.0.0')];
    const errors = validateAssistantToolCommands(
      [
        {bindingId: 'one', enabled: true, sortOrder: 0},
        {bindingId: 'two', enabled: true, sortOrder: 1},
      ],
      bindings,
    );
    expect(errors).toContain('同一助手不能启用工具的多个版本：article.summary');
  });
});
