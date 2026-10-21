import {useEffect, useRef, useState} from 'react';
import {z} from 'zod';
import {AiApiError} from '@/services/arte-ai';
import {currentAiCredential} from '@/services/arte-ai/request';
import {cancelRun, submitToolChat, type SubmitToolChatRequest, type ToolChatStatus,} from '@/services/arte-ai/toolChat';
import {type BrowserContext, readValue, removeValue, sameScope, storageKey, writeValue,} from './storage';

const id = z.string().min(1).max(256);
const scope = z.object({tenantId: id, workspaceId: id});
const ref = z.object({type: id, id, version: id});
const savedSchema = z.object({
  key: id,
  readOnly: z.boolean(),
  acceptedId: id.optional(),
  request: z.object({
    scope,
    conversationId: id,
    expectedVersion: z.number().int().nonnegative(),
    text: z.string().min(1).max(1_000_000),
    binding: ref.extend({type: z.literal('binding')}),
    capability: ref.extend({type: z.literal('capability')}),
    modelBudgetRef: id,
    maxInputTokens: z.number().int().positive(),
    generationOptions: z.object({
      maxOutputTokens: z.number().int().positive(),
      temperature: z.number().nullish(),
      topP: z.number().nullish(),
      stopSequences: z.array(z.string()),
    }),
    toolBindingIds: z.array(id).min(1).max(128),
    timeoutSeconds: z.number().int().min(1).max(3600),
    maxModelRounds: z.number().int().min(1).max(32),
    maxToolCalls: z.number().int().min(1).max(100),
    maxConcurrentTools: z.number().int().min(1).max(16),
    budgetLimits: z
      .array(
        z.object({
          currency: z.string().regex(/^[A-Z]{3}$/),
          amount: z.string().regex(/^[0-9]{1,18}(\.[0-9]{1,18})?$/),
        }),
      )
      .min(1)
      .max(8),
  }),
});
type Saved = z.infer<typeof savedSchema>;
const stopSchema = z.object({key: id, runId: id, scope});

/** 先保存完整命令再发请求；已受理只查原 Run，不在卸载时取消业务。 */
export function useToolChatCommand(
  context: BrowserContext,
  conversationId: string,
  onAccepted: (id: string, text: string) => void,
) {
  const target = storageKey(context, 'tool-chat-send', conversationId),
    stopTarget = storageKey(context, 'tool-chat-stop', conversationId);
  const [restored] = useState(() => {
    const read = readValue<unknown>(target),
      stop = readValue<unknown>(stopTarget);
    const parsed = savedSchema.safeParse(read.value),
      stopping = stopSchema.safeParse(stop.value);
    const saved =
      parsed.success &&
      sameScope(parsed.data.request.scope, context.scope) &&
      parsed.data.request.conversationId === conversationId
        ? parsed.data
        : null;
    const cancel =
      stopping.success && sameScope(stopping.data.scope, context.scope)
        ? stopping.data
        : null;
    return {
      saved,
      cancel,
      blocked:
        !!read.warning ||
        !!stop.warning ||
        (read.value !== null && !saved) ||
        (stop.value !== null && !cancel),
    };
  });
  const [saved, setSaved] = useState<Saved | null>(restored.saved);
  const command = useRef(saved);
  const [stop, setStop] = useState(restored.cancel);
  const stopping = useRef(stop);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [storageError, setStorageError] = useState(restored.blocked);
  const active = useRef<AbortController | null>(null),
    alive = useRef(true);
  const accepted = useRef(onAccepted);
  const observedCommands = useRef(new Map<string, ToolChatStatus>());
  accepted.current = onAccepted;
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (storageError && (command.current || stopping.current || active.current)) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [storageError]);
  useEffect(() => {
    alive.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent).detail === context.userId) {
        alive.current = false;
        active.current?.abort();
      }
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      alive.current = false;
      active.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId]);
  const persist = (value: Saved) => {
    if (!writeValue(target, value)) {
      setStorageError(true);
      return false;
    }
    command.current = value;
    setSaved(value);
    return true;
  };

  async function send(request?: SubmitToolChatRequest, readOnly = false) {
    if (
      !alive.current ||
      active.current ||
      restored.blocked ||
      command.current?.acceptedId
    )
      return;
    const value =
      command.current ??
      (request ? {request, readOnly, key: crypto.randomUUID()} : null);
    if (!value || !savedSchema.safeParse(value).success || !persist(value))
      return;
    const controller = new AbortController(),
      credential = currentAiCredential();
    active.current = controller;
    setBusy(true);
    setError(null);
    try {
      const response = await submitToolChat(
        value.request,
        value.key,
        value.readOnly,
        {signal: controller.signal},
      );
      if (
        !alive.current ||
        controller.signal.aborted ||
        credential !== currentAiCredential()
      )
        return;
      if (
        response.httpStatus !== 202 ||
        response.body.data.kind !== 'RUN' ||
        !response.body.data.executionId
      )
        throw new Error('Invalid tool chat acceptance');
      const next = {...value, acceptedId: response.body.data.executionId};
      // 即使受理后的存储失败，当前页也必须保留已知的 Run 身份，禁止创建新操作。
      command.current = next;
      setSaved(next);
      if (!writeValue(target, next)) setStorageError(true);
      accepted.current(next.acceptedId, value.request.text);
    } catch (failure) {
      if (
        !alive.current ||
        controller.signal.aborted ||
        credential !== currentAiCredential()
      )
        return;
      setError(failure);
      if (
        failure instanceof AiApiError &&
        failure.httpStatus >= 400 &&
        failure.httpStatus < 500 &&
        failure.httpStatus !== 408 &&
        failure.code !== '205027'
      ) {
        if (removeValue(target)) {
          command.current = null;
          setSaved(null);
        } else setStorageError(true);
      }
    } finally {
      if (active.current === controller) active.current = null;
      if (alive.current && credential === currentAiCredential()) setBusy(false);
    }
  }

  async function cancel(runId?: string) {
    if (!alive.current || active.current || restored.blocked) return;
    const value =
      stopping.current ??
      (runId
        ? {scope: context.scope, runId, key: crypto.randomUUID()}
        : null);
    if (!value) return;
    if (!writeValue(stopTarget, value)) {
      setStorageError(true);
      return;
    }
    stopping.current = value;
    setStop(value);
    const controller = new AbortController(),
      credential = currentAiCredential();
    active.current = controller;
    setBusy(true);
    setError(null);
    try {
      const response = await cancelRun(value.scope, value.runId, value.key, {
        signal: controller.signal,
      });
      if (
        !alive.current ||
        controller.signal.aborted ||
        credential !== currentAiCredential()
      )
        return;
      if (response.body.data.runId !== value.runId)
        throw new Error('Run cancel identity mismatch');
      // 仍保留同一停止命令，直到权威状态确认停止事实或原 Run 结束。
    } catch (failure) {
      if (
        alive.current &&
        !controller.signal.aborted &&
        credential === currentAiCredential()
      )
        setError(failure);
    } finally {
      if (active.current === controller) active.current = null;
      if (alive.current && credential === currentAiCredential()) setBusy(false);
    }
  }

  function observed(value: ToolChatStatus) {
    if (command.current?.acceptedId === value.runId || stopping.current?.runId === value.runId)
      observedCommands.current.set(value.runId, value);
    const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(
      value.state,
    );
    if (
      stopping.current?.runId === value.runId &&
      (terminal || value.cancellationRequested)
    ) {
      if (removeValue(stopTarget)) {
        stopping.current = null;
        setStop(null);
      } else setStorageError(true);
    }
    if (command.current?.acceptedId === value.runId && terminal) {
      if (removeValue(target)) {
        command.current = null;
        setSaved(null);
      } else setStorageError(true);
    }
  }

  function recheckStorage() {
    if (restored.blocked) return;
    const probe = storageKey(
      context,
      'tool-chat-storage-probe',
      conversationId,
    );
    if (
      writeValue(probe, true) &&
      removeValue(probe) &&
      (!command.current || writeValue(target, command.current))
    ) {
      setStorageError(false);
      for (const value of observedCommands.current.values()) observed(value);
      if (!command.current && !stopping.current) observedCommands.current.clear();
    }
  }

  return {
    saved,
    stop,
    busy,
    error,
    storageError,
    blocked: restored.blocked,
    send,
    cancel,
    observed,
    recheckStorage,
  };
}
