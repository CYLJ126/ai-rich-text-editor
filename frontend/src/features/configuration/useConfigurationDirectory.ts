import {useCallback, useEffect, useRef, useState} from 'react';
import {AiApiError} from '@/services/arte-ai/request';

type Page<T> = { records: T[]; nextCursor: string | null; exhausted: boolean };
type Directory<T> = Page<T> & { loading: boolean; expired: boolean; error: string };
const initial = {records: [], nextCursor: null, exhausted: false, loading: true, expired: false, error: ''};

export function configurationReadError(failure: unknown, fallback: string) {
  if (failure instanceof AiApiError) {
    if (failure.httpStatus === 401) return '登录已失效，请重新登录。';
    if ([403, 404].includes(failure.httpStatus)) return '当前配置对象不可访问，访问资格可能已变化。';
    if (failure.code === 'PLATFORM_AUTHORITY_UNAVAILABLE' || failure.httpStatus === 503)
      return '授权服务暂不可用，请稍后重试。';
  }
  return fallback;
}

/** 本页分页状态。换筛选条件或目标后中止旧读取，空页可继续。 */
export function useConfigurationDirectory<T>(
  fetchPage: (cursor: string | null, signal: AbortSignal) => Promise<Page<T>>,
  keyOf: (value: T) => string,
) {
  const [state, setState] = useState<Directory<T>>(initial);
  const reader = useRef<AbortController | null>(null);
  const read = useCallback(async (cursor: string | null, previous: T[] = []) => {
    reader.current?.abort();
    const request = new AbortController();
    reader.current = request;
    setState(current => ({...(cursor ? current : initial), loading: true, error: ''}));
    try {
      const page = await fetchPage(cursor, request.signal);
      if (request.signal.aborted) return;
      const records = [...previous, ...page.records];
      if ((cursor && page.nextCursor === cursor) || new Set(records.map(keyOf)).size !== records.length)
        throw new Error('目录游标无效');
      setState({...page, records, loading: false, error: '', expired: false});
    } catch (failure) {
      if (request.signal.aborted) return;
      const expired = failure instanceof AiApiError && failure.code === 'PLATFORM_CURSOR_EXPIRED';
      const inaccessible = failure instanceof AiApiError && [401, 403, 404].includes(failure.httpStatus);
      setState(current => ({
        ...(inaccessible ? initial : current), expired, loading: false,
        error: expired ? '目录已过期，请从第一页重新读取。' : configurationReadError(failure, '目录读取失败，请重试。')
      }));
    }
  }, [fetchPage, keyOf]);

  useEffect(() => {
    void read(null);
    return () => reader.current?.abort();
  }, [read]);

  return {
    ...state,
    reload: () => void read(null),
    more: () => {
      if (!state.loading && !state.expired && !state.exhausted && state.nextCursor)
        void read(state.nextCursor, state.records);
    },
  };
}
