/** 真实 HTTP 固定测试会话；仅验收按钮可模拟一次服务端已提交但响应丢失。 */
export let loseNextPreparationResponse = false;

export function simulatePreparationResponseLoss() {
  loseNextPreparationResponse = true;
}

export async function request(url: string, options: {
  headers: Record<string, string>;
  data: unknown;
  signal?: AbortSignal
}) {
  const response = await fetch(url, {
    method: 'POST',
    headers: options.headers,
    body: JSON.stringify(options.data),
    signal: options.signal
  });
  const data = await response.json();
  if (url.endsWith('/prepareModelUsage') && response.ok && loseNextPreparationResponse) {
    loseNextPreparationResponse = false;
    throw new TypeError('模拟：服务端已提交，响应在浏览器前丢失');
  }
  return {status: response.status, data};
}

export const getLocale = () => 'zh-CN';
