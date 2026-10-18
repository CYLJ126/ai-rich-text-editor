import {useCallback, useEffect, useRef, useState} from 'react';
import type {AiHttpResponse, AiResult} from '@/services/arte-ai';
import type {ToolPage} from '@/services/arte-ai/tool';
import {toolFailure} from './useToolCommand';

/** 空页仍按游标继续；筛选变化／卸载取消原读取，迟到响应不会覆盖当前页。 */
export function useToolPage<T>(
  load: (
    cursor: string | null,
    signal: AbortSignal,
  ) => Promise<AiHttpResponse<AiResult<ToolPage<T>>>>,
) {
  const [state, setState] = useState<{
    records: T[];
    cursor: string | null;
    exhausted: boolean;
    loading: boolean;
    error: string | null;
  }>({
    records: [],
    cursor: null,
    exhausted: false,
    loading: true,
    error: null,
  });
  const active = useRef<AbortController | null>(null);
  const current = useRef(state);
  current.current = state;
  const read = useCallback(
    async (append: boolean) => {
      active.current?.abort();
      const controller = new AbortController();
      active.current = controller;
      const previous = current.current;
      setState((value) => ({
        ...value,
        loading: true,
        error: null,
        ...(!append ? {records: [], cursor: null, exhausted: false} : {}),
      }));
      try {
        const {body} = await load(
          append ? previous.cursor : null,
          controller.signal,
        );
        if (controller.signal.aborted) return;
        const page = body.data;
        if (
          !Array.isArray(page.records) ||
          typeof page.exhausted !== 'boolean' ||
          (page.nextCursor !== null && typeof page.nextCursor !== 'string') ||
          page.exhausted !== (page.nextCursor === null) ||
          (append && previous.cursor === page.nextCursor && !page.exhausted)
        )
          throw new Error('目录游标未正常推进，请刷新目录。');
        setState({
          records: append
            ? [...previous.records, ...page.records]
            : page.records,
          cursor: page.nextCursor,
          exhausted: page.exhausted,
          loading: false,
          error: null,
        });
      } catch (error) {
        if (!controller.signal.aborted)
          setState((value) => ({
            ...value,
            loading: false,
            error: toolFailure(error),
          }));
      }
    },
    [load],
  );
  useEffect(() => {
    void read(false);
    return () => active.current?.abort();
  }, [read]);
  return {
    ...state,
    refresh: () => void read(false),
    more: () => {
      if (!current.current.loading && !current.current.exhausted)
        void read(true);
    },
  };
}
