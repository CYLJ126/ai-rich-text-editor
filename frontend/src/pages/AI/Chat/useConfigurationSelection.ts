import {useEffect, useRef, useState} from 'react';
import {
  type AiScope,
  type BudgetAccountResponse,
  type ChatModelOption,
  discoverChatOptions,
  getBudget,
} from '@/services/arte-ai';

/** 每次显式加载替换旧请求；scope 变化／卸载后忽略迟到响应，包括不遵守 signal 的传输。 */
export function useConfigurationDiscovery() {
  const request = useRef<AbortController | null>(null);
  const [state, setState] = useState<{
    scope: AiScope | null;
    options: ChatModelOption[];
    loading: boolean;
    error: unknown;
  }>({scope: null, options: [], loading: false, error: null});

  function clear() {
    request.current?.abort();
    request.current = null;
    setState({scope: null, options: [], loading: false, error: null});
  }

  async function load(scope: AiScope): Promise<ChatModelOption[] | null> {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setState({scope, options: [], loading: true, error: null});
    try {
      const {body} = await discoverChatOptions(
        {scope},
        {signal: controller.signal},
      );
      if (controller.signal.aborted || request.current !== controller)
        return null;
      setState({
        scope,
        options: body.data.options,
        loading: false,
        error: null,
      });
      return body.data.options;
    } catch (error) {
      if (!controller.signal.aborted && request.current === controller) {
        setState({scope, options: [], loading: false, error});
      }
      return null;
    }
  }

  useEffect(
    () => () => {
      request.current?.abort();
    },
    [],
  );
  return {...state, load, clear};
}

export function useSelectedBudget(
  scope: AiScope | null,
  budgetRef: string | undefined,
) {
  const key =
    scope && budgetRef
      ? JSON.stringify([scope.tenantId, scope.workspaceId, budgetRef])
      : null;
  const [revision, refresh] = useState(0);
  const [state, setState] = useState<{
    key: string | null;
    account: BudgetAccountResponse | null;
    loading: boolean;
    error: unknown;
  }>({key: null, account: null, loading: false, error: null});
  useEffect(() => {
    if (!scope || !budgetRef || !key) return;
    const controller = new AbortController();
    setState({key, account: null, loading: true, error: null});
    void getBudget({scope, budgetRef}, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted)
          setState({key, account: body.data, loading: false, error: null});
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setState({key, account: null, loading: false, error});
      });
    return () => controller.abort();
  }, [key, revision]);
  // scope／账户切换后的第一帧也不能展示旧余额。
  const current =
    key && state.key === key
      ? state
      : {account: null, loading: !!key, error: null};
  return {...current, refresh: () => refresh((value) => value + 1)};
}
