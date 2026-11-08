/** 隔离浏览器验收的 Umi 传输适配；业务服务、取消、错误及 SSE 解析仍使用生产实现。 */
export const getLocale = () => 'zh-CN';

export async function request(url: string, options: {
  method: string;
  headers: Record<string, string>;
  data: unknown;
  signal?: AbortSignal
}) {
  const metrics = (window as any).__er6Metrics;
  const started = performance.now();
  const response = await fetch(url, {
    method: options.method, headers: options.headers,
    body: JSON.stringify(options.data), signal: options.signal
  });
  const data = await response.json();
  if (metrics && url.startsWith('/arte/')) metrics.reads.push({url, started, completed: performance.now()});
  return {response, data};
}
