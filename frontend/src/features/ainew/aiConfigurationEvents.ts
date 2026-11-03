import type {WorkspaceScope} from '@/services/platform/workspace';
import {sameScope} from './storage';

const channel = 'arte-ai-configuration-changed';
const key = 'arte.ai.configuration.revision';
type Change = { userId: string; scope: WorkspaceScope; revision: string };

/** 通知只携带账号、范围与失效标记，不含密钥、额度或许可。 */
export function notifyAiConfiguration(userId: string, scope: WorkspaceScope) {
  const change: Change = {userId, scope, revision: crypto.randomUUID()};
  window.dispatchEvent(new CustomEvent(channel, {detail: change}));
  try {
    localStorage.setItem(key, JSON.stringify(change));
  } catch { /* 同页仍收到通知，跨页可手动重读。 */
  }
}

export function subscribeAiConfiguration(userId: string, scope: WorkspaceScope, changed: () => void) {
  const accept = (value: unknown) => {
    const change = value as Partial<Change> | null;
    if (change?.userId === userId && typeof change.revision === 'string' && sameScope(change.scope, scope)) changed();
  };
  const local = (event: Event) => accept((event as CustomEvent).detail);
  const remote = (event: StorageEvent) => {
    if (event.key !== key || !event.newValue) return;
    try {
      accept(JSON.parse(event.newValue));
    } catch { /* 不消费损坏通知。 */
    }
  };
  window.addEventListener(channel, local);
  window.addEventListener('storage', remote);
  return () => {
    window.removeEventListener(channel, local);
    window.removeEventListener('storage', remote);
  };
}
