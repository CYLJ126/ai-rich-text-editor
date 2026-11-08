import {recoveryRead} from './recoveryRead';
import {currentAiCredential} from '@/services/arte-ai/request';
import {useCallback, useEffect, useRef, useState} from 'react';
import {type AiScope, type ConversationExecutionState, getConversationExecutionState} from '@/services/arte-ai';

/**
 * 会话占用的独立只读观察器；按请求序号接纳结果，同版本也更新，取消或切换后的响应无效。
 * 页面以该权威投影结合历史、配置和预算决定动作；同版本的占用释放也必须接纳。
 */
export function useConversationExecutionState(scope: AiScope, conversationId: string, automatic = true) {
  const [view, setView] = useState<{
    key: string;
    state?: ConversationExecutionState;
    loading: boolean;
    error?: unknown
  }>();
  const key = JSON.stringify([currentAiCredential(), scope.tenantId, scope.workspaceId, conversationId]);
  const currentKey = useRef(key);
  currentKey.current = key;
  const sequence = useRef(0);
  const snapshot = useRef<ConversationExecutionState | undefined>(undefined);
  const snapshotKey = useRef<string | undefined>(undefined);
  const pending = useRef<{
    key: string;
    controller: AbortController;
    promise: Promise<ConversationExecutionState | undefined>
  } | undefined>(undefined);
  const tenantId = scope.tenantId;
  const workspaceId = scope.workspaceId;

  const refresh = useCallback(() => {
    if (currentKey.current !== key) return Promise.resolve(undefined as ConversationExecutionState | undefined);
    if (pending.current?.key === key) return pending.current.promise;
    pending.current?.controller.abort();
    const controller = new AbortController();
    const requestSequence = ++sequence.current;
    const latest = () => !controller.signal.aborted && sequence.current === requestSequence && currentKey.current === key;
    snapshot.current = undefined;
    setView({key, loading: true});
    const promise = recoveryRead(signal => getConversationExecutionState({
      scope: {tenantId, workspaceId}, conversationId
    }, {signal}), controller.signal)
      .then(response => {
        if (!latest()) return undefined;
        snapshotKey.current = key;
        snapshot.current = response.body.data;
        setView({key, state: response.body.data, loading: false});
        return response.body.data;
      }, error => {
        if (latest()) setView({key, loading: false, error});
        throw error;
      }).finally(() => {
        if (pending.current?.controller === controller) pending.current = undefined;
      });
    pending.current = {key, controller, promise};
    return promise;
  }, [key, tenantId, workspaceId, conversationId]);

  useEffect(() => {
    if (automatic && document.visibilityState !== 'hidden') void refresh().catch(() => {
    });
    return () => {
      ++sequence.current;
      pending.current?.controller.abort();
      pending.current = undefined;
    };
  }, [refresh, automatic]);

  const visible = view?.key === key ? view : undefined;
  return {
    state: visible?.state, loading: visible?.loading ?? true, error: visible?.error, refresh,
    current: () => currentKey.current === key && snapshotKey.current === key ? snapshot.current : undefined,
    invalidate: () => {
      ++sequence.current;
      pending.current?.controller.abort();
      pending.current = undefined;
      snapshot.current = undefined;
      setView({key, loading: false});
    }
  };
}
