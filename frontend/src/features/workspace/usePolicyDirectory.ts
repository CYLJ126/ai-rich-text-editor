import {useCallback, useEffect, useRef, useState} from 'react';
import {AiApiError} from '@/services/arte-ai/request';
import {browseAccessibleWorkspaces, type WorkspaceSummary} from '@/services/platform/workspace';

/** 政策页的只读候选目录，不复用或更改其他页面的目录搜索词和空间偏好。 */
export function usePolicyDirectory(userId: string) {
  const [records, setRecords] = useState<WorkspaceSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [exhausted, setExhausted] = useState(false);
  const [expired, setExpired] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const reader = useRef<AbortController | null>(null);
  const saved = useRef<WorkspaceSummary[]>([]);

  const read = useCallback(async (continuation: string | null) => {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    if (!continuation) {
      saved.current = [];
      setRecords([]);
      setCursor(null);
      setExhausted(false);
      setExpired(false);
    }
    try {
      const {body: {data}} = await browseAccessibleWorkspaces(continuation, '', {signal: controller.signal});
      if (controller.signal.aborted) return;
      if (data.nextCursor && data.nextCursor === continuation) throw new Error('目录未向后推进，请刷新可选范围。');
      const next = [...saved.current, ...data.records];
      if (new Set(next.map(item => JSON.stringify([item.scope.tenantId, item.scope.workspaceId]))).size !== next.length)
        throw new Error('目录返回重复空间，请刷新可选范围。');
      saved.current = next;
      setRecords(next);
      setCursor(data.nextCursor);
      setExhausted(data.exhausted);
    } catch (failure) {
      if (controller.signal.aborted) return;
      setError(failure instanceof Error ? failure.message : '可选租户和空间读取失败，请重试。');
      if (failure instanceof AiApiError && failure.code === 'PLATFORM_CURSOR_EXPIRED') setExpired(true);
      if (failure instanceof AiApiError && failure.httpStatus === 401) {
        saved.current = [];
        setRecords([]);
        setCursor(null);
      }
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (userId) void read(null);
    return () => reader.current?.abort();
  }, [userId, read]);

  return {
    records, exhausted, expired, loading, error, hasMore: !!cursor && !exhausted && !expired,
    reload: () => read(null),
    more: () => !loading && !expired && cursor ? read(cursor) : Promise.resolve(),
  };
}
