import {currentAiCredential} from '@/services/arte-ai/request';
import {useCallback, useEffect, useRef, useState} from 'react';
import {type AiScope, type ConversationExecutionState, getConversationExecutionState} from '@/services/arte-ai';

/**
 * 会话占用的独立只读观察器；按请求序号接纳结果，同版本也更新，取消或切换后的响应无效。
 * 本阶段不从本投影派生发送资格；后续页面恢复流程负责决定刷新时机和有限重试。
 */
export function useConversationExecutionState(scope: AiScope, conversationId: string) {
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
  const pending = useRef<{ key: string; controller: AbortController; promise: Promise<void> } | undefined>(undefined);
  const tenantId = scope.tenantId;
  const workspaceId = scope.workspaceId;

  const refresh = useCallback(() => {
    if (currentKey.current !== key) return Promise.resolve();
    if (pending.current?.key === key) return pending.current.promise;
    pending.current?.controller.abort();
    const controller = new AbortController();
    const requestSequence = ++sequence.current;
    const latest = () => !controller.signal.aborted && sequence.current === requestSequence && currentKey.current === key;
    setView({key, loading: true});
    const promise = getConversationExecutionState({
      scope: {tenantId, workspaceId},
      conversationId
    }, {signal: controller.signal})
      .then(response => {
        if (latest()) setView({key, state: response.body.data, loading: false});
      }, error => {
        if (latest()) setView({key, loading: false, error});
      }).finally(() => {
        if (pending.current?.controller === controller) pending.current = undefined;
      });
    pending.current = {key, controller, promise};
    return promise;
  }, [key, tenantId, workspaceId, conversationId]);

  useEffect(() => {
    void refresh();
    return () => {
      ++sequence.current;
      pending.current?.controller.abort();
      pending.current = undefined;
    };
  }, [refresh]);

  const visible = view?.key === key ? view : undefined;
  return {state: visible?.state, loading: visible?.loading ?? true, error: visible?.error, refresh};
}
