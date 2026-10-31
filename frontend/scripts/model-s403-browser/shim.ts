/** 仅替代宿主认证传输；业务请求通过 HTTP 进入正式 MVC、公共授权和隔离数据库。 */
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
  return {status: response.status, data: await response.json()};
}
