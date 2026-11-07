import {useEffect, useRef, useState} from 'react';
import {AiApiError} from '@/services/arte-ai/request';
import {type AssistantCommand, type AssistantContent, executeAssistantCommand} from '@/services/arte-ai/assistant';
import {type BrowserContext, type Operation, readOperation, saveOperation, STORAGE_WARNING} from './storage';

export function assistantError(error: unknown) {
  if (error instanceof AiApiError && error.desc) return error.desc;
  return '请求未能完成，请检查连接后重试。';
}

/** 以目标和刷新代次隔离读结果，避免切换后短暂显示上一条数据。 */
export function useAssistantRead<T>(key: string | null, load: (signal: AbortSignal) => Promise<T>, keepValueOnReload = false) {
  const [epoch, setEpoch] = useState(0);
  const [result, setResult] = useState<{ key: string; epoch: number; value: T | null; error: unknown }>();
  const loader = useRef(load);
  loader.current = load;
  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController();
    void loader.current(controller.signal).then(value => {
      if (!controller.signal.aborted) setResult({key, epoch, value, error: null});
    }, error => {
      if (!controller.signal.aborted) setResult({key, epoch, value: null, error});
    });
    return () => controller.abort();
  }, [key, epoch]);
  const matching = result?.key === key ? result : null;
  const current = matching?.epoch === epoch ? matching : null;
  return {
    value: (current ?? (keepValueOnReload ? matching : null))?.value ?? null,
    error: current?.error, loading: key !== null && !current,
    reload: () => setEpoch(value => value + 1),
  };
}

const operationKind = 'assistant-command';

/** 先保存原请求再提交。结果不确定时只允许用原键确认。 */
export function useAssistantCommand(context: BrowserContext, accepted: (value: Awaited<ReturnType<typeof executeAssistantCommand>>, request: AssistantCommand) => void) {
  const [recovery] = useState(() => readOperation<AssistantCommand>(context, operationKind, ''));
  const [pending, setPending] = useState(recovery.value);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  useEffect(() => {
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== context.userId) return;
      request.current?.abort();
      request.current = null;
      setPending(null);
      setBusy(false);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      request.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId]);

  async function submit(command?: AssistantCommand) {
    if (request.current || recovery.warning || (command && pending)) return;
    const operation: Operation<AssistantCommand> | null = command ? {
      key: crypto.randomUUID(),
      request: command
    } : pending;
    if (!operation) return;
    if (command && !saveOperation(context, operationKind, '', operation)) {
      setError('原操作无法保存到浏览器，提交未发起。');
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setPending(operation);
    setBusy(true);
    setError(null);

    function clear() {
      if (saveOperation(context, operationKind, '', null)) {
        setPending(null);
        return true;
      }
      setError(STORAGE_WARNING);
      return false;
    }

    try {
      const result = await executeAssistantCommand(operation.request, operation.key, {signal: controller.signal});
      if (controller.signal.aborted) return;
      clear();
      accepted(result, operation.request);
    } catch (failure) {
      if (controller.signal.aborted) return;
      const rejected = failure instanceof AiApiError && failure.httpStatus >= 400 && failure.httpStatus < 500
        && failure.httpStatus !== 408 && failure.code !== '205027';
      if (!rejected || clear()) setError(assistantError(failure));
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return {submit, pending, busy, error, warning: recovery.warning};
}

export const assistantStateLabel = {DRAFT: '草稿', ACTIVE: '已发布', DISABLED: '已停用'};

export const emptyAssistant: AssistantContent = {
  displayName: '', description: '', systemInstruction: '', expectedOutput: '', examplePrompts: [],
};

export function contentError(content: AssistantContent) {
  const length = (value: string) => Array.from(value.trim()).length;
  if (length(content.displayName) > 80) return '名称最多 80 个字符。';
  if (length(content.description) > 500) return '用途最多 500 个字符。';
  if (new TextEncoder().encode(content.systemInstruction.trim()).length > 8192) return '工作指令最多 8192 字节。';
  if (length(content.expectedOutput) > 1000) return '预期输出最多 1000 个字符。';
  if (content.examplePrompts.some(prompt => length(prompt) > 500)) return '每条示例最多 500 个字符。';
  return null;
}

export function completeAssistant(content: AssistantContent) {
  return [content.displayName, content.description, content.systemInstruction, content.expectedOutput].every(value => value.trim())
    && content.examplePrompts.length >= 1 && content.examplePrompts.every(value => value.trim());
}
