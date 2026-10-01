import {describe, expect, it} from 'vitest';
import access from './access';

describe('personal tool configuration permissions', () => {
  it('allows invoking users to configure their tools without global management rights', () => {
    const permissions = access({currentUser: {menuOperations: ['aiTool:list', 'aiTool:invoke']} as API.CurrentUser});
    expect(permissions.canViewAiTools).toBe(true);
    expect(permissions.canConfigureAiTools).toBe(true);
    expect(permissions.canManageAiTools).toBe(false);
  });
  it('keeps viewing permission read only', () => {
    const permissions = access({currentUser: {menuOperations: ['aiTool:list']} as API.CurrentUser});
    expect(permissions.canConfigureAiTools).toBe(false);
    expect(permissions.canManageAiTools).toBe(false);
  });
  it('retains administrator access', () => {
    const permissions = access({currentUser: {roles: ['admin']} as API.CurrentUser});
    expect(permissions.canConfigureAiTools).toBe(true);
    expect(permissions.canManageAiTools).toBe(true);
  });
});
