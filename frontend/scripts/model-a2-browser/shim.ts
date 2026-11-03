/** M5 固定测试会话；所有业务数据来自真实本机 MVC／H2，绝不连接业务后端。 */
import {useSyncExternalStore} from 'react';
import {createBrowserHistory} from 'history';

export const history = createBrowserHistory();
const listeners = new Set<() => void>();
export let login = 'alice';
let snapshot = {currentUser: {id: '1', menus: ['EnvironmentModels', 'EnvironmentWorkspaces']}};
const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
export const useModel = () => ({initialState: useSyncExternalStore(subscribe, () => snapshot)});

export function setFixtureSession(name: string, id: string) {
  window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: snapshot.currentUser.id}));
  login = name;
  snapshot = {
    currentUser: {
      id,
      menus: name === 'alice' ? ['EnvironmentModels', 'EnvironmentWorkspaces'] : ['EnvironmentModels']
    }
  };
  listeners.forEach(fn => {
    fn();
  });
}

let losePath = '';

export function simulateResponseLoss(path = '/prepareModelUsage') {
  losePath = path;
}

export const simulatePreparationResponseLoss = () => simulateResponseLoss();
let failedRead = '';

export function simulateConfirmedReadFailure() {
  failedRead = 'armed';
}

export async function fixtureFetch(path: string, data?: unknown) {
  const response = await fetch(path, {
    method: data === undefined ? 'GET' : 'POST',
    headers: {'Content-Type': 'application/json', 'X-Model-Fixture-User': login},
    ...(data === undefined ? {} : {body: JSON.stringify(data)})
  });
  if (!response.ok) throw new Error(`隔离请求失败 ${response.status}`);
  return response.json();
}

export async function request(url: string, options: {
  headers: Record<string, string>;
  data: unknown;
  signal?: AbortSignal
}) {
  if (failedRead === 'next' && url.endsWith('/getConfiguration')) {
    failedRead = '';
    throw new TypeError('模拟：确认成功后的重读失败');
  }
  const response = await fetch(url, {
    method: 'POST', headers: {...options.headers, 'X-Model-Fixture-User': login},
    body: JSON.stringify(options.data), signal: options.signal
  });
  const data = await response.json();
  if (response.ok && url.endsWith('/updateConfiguration') && failedRead === 'armed') failedRead = 'next';
  if (losePath && response.ok && url.endsWith(losePath)) {
    losePath = '';
    throw new TypeError('模拟：服务端已提交，响应丢失；只可按原操作确认');
  }
  return {status: response.status, data};
}
export const getLocale = () => 'zh-CN';
export const useIntl = () => ({
  formatMessage: ({defaultMessage, id}: {
    defaultMessage?: string;
    id: string
  }) => defaultMessage ?? id
});
