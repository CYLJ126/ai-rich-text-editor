import {afterEach, expect, it, vi} from 'vitest';
import {notifyAiConfiguration, subscribeAiConfiguration} from './aiConfigurationEvents';

const scope = {tenantId: 't', workspaceId: 'A'};
afterEach(() => vi.restoreAllMocks());
it('invalidates only the same account and full scope, including storage events', () => {
  const changed = vi.fn();
  const stop = subscribeAiConfiguration('alice', scope, changed);
  notifyAiConfiguration('bob', scope);
  notifyAiConfiguration('alice', {...scope, tenantId: 'other'});
  expect(changed).not.toHaveBeenCalled();
  notifyAiConfiguration('alice', scope);
  expect(changed).toHaveBeenCalledTimes(1);
  const wire = localStorage.getItem('arte.ai.configuration.revision')!;
  expect(Object.keys(JSON.parse(wire)).sort()).toEqual(['revision', 'scope', 'userId']);
  window.dispatchEvent(new StorageEvent('storage', {key: 'arte.ai.configuration.revision', newValue: wire}));
  expect(changed).toHaveBeenCalledTimes(2);
  window.dispatchEvent(new StorageEvent('storage', {key: 'arte.ai.configuration.revision', newValue: 'damaged'}));
  expect(changed).toHaveBeenCalledTimes(2);
  stop();
  notifyAiConfiguration('alice', scope);
  expect(changed).toHaveBeenCalledTimes(2);
});
it('keeps same-page notification when cross-tab storage is blocked', () => {
  const changed = vi.fn(), stop = subscribeAiConfiguration('alice', scope, changed);
  vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  expect(() => notifyAiConfiguration('alice', scope)).not.toThrow();
  expect(changed).toHaveBeenCalledTimes(1);
  stop();
});

it('notifies spaces in the same tenant after tenant policy changes without crossing accounts', () => {
  const sameTenant = vi.fn(), otherTenant = vi.fn(), otherAccount = vi.fn();
  const stop = [subscribeAiConfiguration('alice', {...scope, workspaceId: 'B'}, sameTenant),
    subscribeAiConfiguration('alice', {...scope, tenantId: 'other'}, otherTenant),
    subscribeAiConfiguration('bob', scope, otherAccount)];
  notifyAiConfiguration('alice', scope, true);
  expect(sameTenant).toHaveBeenCalledTimes(1);
  expect(otherTenant).not.toHaveBeenCalled();
  expect(otherAccount).not.toHaveBeenCalled();
  stop.forEach(close => close());
});
