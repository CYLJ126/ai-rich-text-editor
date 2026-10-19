/** 仅替代宿主认证传输；工具目录、配置与写命令通过 HTTP 进入真实 MVC／H2。 */
export async function request(
  url: string,
  options: {
    headers: Record<string, string>;
    data: unknown;
    signal?: AbortSignal;
  },
) {
  const response = await fetch(url, {
    method: 'POST',
    headers: options.headers,
    body:
      typeof options.data === 'string'
        ? options.data
        : JSON.stringify(options.data),
    signal: options.signal,
  });
  return {status: response.status, data: await response.json()};
}

export const getLocale = () => 'zh-CN';
