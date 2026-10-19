import {useCallback, useEffect, useRef, useState} from 'react';
import {AiApiError, getInvocationResult, getInvocationStatus,} from '@/services/arte-ai';
import {currentAiCredential} from '@/services/arte-ai/request';
import {cancelToolInvocation, invokeTool, type ToolResult,} from '@/services/arte-ai/toolInvocation';
import type {InvocationResultResponse, InvocationStatusResponse,} from '@/services/arte-ai/types';
import {type BrowserContext, readValue, removeValue, storageKey, writeValue,} from '@/features/ainew/storage';
import {toolFailure} from './useToolCommand';

export function toolInvocationFailure(error: unknown, phase: 'request' | 'submission' | 'confirmation' = 'request'): string {
  if (error instanceof AiApiError) {
    if (error.httpStatus === 504 || error.code === '205005') {
      if (phase === 'confirmation')
        return '确认请求超时，原调用仍待确认。可稍后继续确认，或暂存后开始新的独立调用。';
      return phase === 'submission'
        ? '工具调用受理超时，提交结果尚待确认。可继续确认原调用，或暂存后开始新的独立调用。'
        : '工具请求超时，请稍后重试。';
    }
    if (error.code === '205053')
      return '有效调用方案已变化，输入已保留，请重新预览后确认。';
    if (error.code === '205027')
      return '原调用的幂等语义存在冲突，请保留原操作并核对，勿换键重试。';
    if (error.code === '205055' || error.httpStatus === 400)
      return '参数或调用设置不符合要求，请核对字段和总期限。';
    if (error.code === '205050')
      return '当前工具、配置或本节点实现不可用，请刷新配置后确认。';
    if (error.code === '205052')
      return '当前工具不在已授权的本地免费只读范围内，无法调用。';
    if (error.code === '205056') return '工具等待队列已满，请稍后重新预览。';
  }
  return toolFailure(error);
}

export interface SavedToolInvocation {
  key: string;
  requestJson: string;
  acceptedId?: string;
  cancelKey?: string;
  historyOnly?: boolean;
}

function validRecord(value: unknown, context: BrowserContext): value is SavedToolInvocation {
  try {
    if (!value || typeof value !== 'object') return false;
    const record = value as SavedToolInvocation;
    if (typeof record.requestJson !== 'string' || record.requestJson.length > 1_100_000) return false;
    const body = JSON.parse(record.requestJson);
    return typeof record.key === 'string' && !!record.key && record.key.length <= 256 &&
      body?.scope?.tenantId === context.scope.tenantId &&
      body?.scope?.workspaceId === context.scope.workspaceId &&
      typeof body.input?.bindingId === 'string' && !!body.input.bindingId &&
      /^[0-9a-f]{64}$/.test(body.expectedPlanDigest) &&
      (record.acceptedId === undefined || (typeof record.acceptedId === 'string' && !!record.acceptedId && record.acceptedId.length <= 256)) &&
      (record.cancelKey === undefined || (typeof record.cancelKey === 'string' && !!record.cancelKey && record.cancelKey.length <= 256)) &&
      (record.historyOnly === undefined || typeof record.historyOnly === 'boolean');
  } catch {
    return false;
  }
}

export const toolTerminal = (status: InvocationStatusResponse | null) =>
  !!status && !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(status.state);
export type ToolResultView = { value: ToolResult; outputJson: string | null };

/** 每个账号／空间保存原命令；恢复只查询原 ID，未获回执时由用户用原键确认。 */
export function useToolInvocation(context: BrowserContext) {
  const target = storageKey(context, 'tool-invocation');
  const historyTarget = storageKey(context, 'tool-invocation-history');
  const pendingTarget = storageKey(context, 'tool-invocation-pending');
  const [pendingInitial] = useState(() => {
    const stored = readValue<unknown>(pendingTarget);
    if (stored.value === null) return {records: [] as SavedToolInvocation[], blocked: !!stored.warning};
    if (!Array.isArray(stored.value) || stored.value.length > 20 ||
      !stored.value.every(value => validRecord(value, context) && !value.acceptedId && !value.historyOnly) ||
      new Set(stored.value.map(value => value.key)).size !== stored.value.length)
      return {records: [] as SavedToolInvocation[], blocked: true};
    return {records: stored.value as SavedToolInvocation[], blocked: false};
  });
  const [pending, setPending] = useState(pendingInitial.records);
  const pendingRef = useRef(pending);
  const [history, setHistory] = useState<string[]>(() => {
    const stored = readValue<unknown>(historyTarget).value;
    return Array.isArray(stored) &&
    stored.length <= 20 &&
    stored.every(
      (id) => typeof id === 'string' && id.length > 0 && id.length <= 256,
    )
      ? stored
      : [];
  });
  const [initial] = useState(() => {
    const stored = readValue<SavedToolInvocation>(target);
    if (!stored.value) return {record: null, blocked: !!stored.warning};
    return validRecord(stored.value, context)
      ? {record: stored.value, blocked: false}
      : {record: null, blocked: true};
  });
  const [record, setRecord] = useState<SavedToolInvocation | null>(
    initial.record,
  );
  const recordRef = useRef(record);
  const [blocked, setBlocked] = useState(initial.blocked || pendingInitial.blocked);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(
    initial.blocked || pendingInitial.blocked
      ? '调用恢复记录无法读取，暂不能创建新调用。请检查浏览器存储后刷新。'
      : null,
  );
  const [status, setStatus] = useState<InvocationStatusResponse | null>(null);
  const [result, setResult] = useState<ToolResultView | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [cancelReceipt, setCancelReceipt] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  const mounted = useRef(true);
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      active.current?.abort();
    };
  }, []);
  const save = (next: SavedToolInvocation) => {
    if (!writeValue(target, next)) {
      setBlocked(true);
      setError('无法保存调用恢复记录，请检查浏览器存储；尚未发送新操作。');
      return false;
    }
    recordRef.current = next;
    setRecord(next);
    return true;
  };
  const savePending = (next: SavedToolInvocation[]) => {
    if (!writeValue(pendingTarget, next)) {
      setError('无法保存待确认调用，原提交仍保留在当前页面；请检查浏览器存储。');
      return false;
    }
    pendingRef.current = next;
    setPending(next);
    return true;
  };
  const releaseCurrent = () => {
    if (!removeValue(target)) {
      setBlocked(true);
      setError('无法更新当前调用恢复记录，请检查浏览器存储后刷新。');
      return false;
    }
    recordRef.current = null;
    setRecord(null);
    setStatus(null);
    setResult(null);
    setReadError(null);
    setCancelReceipt(null);
    setError(null);
    return true;
  };
  const execute = async (operation: SavedToolInvocation, recovery: boolean) => {
    if (active.current || blocked) return;
    const controller = new AbortController();
    active.current = controller;
    const credential = currentAiCredential();
    setBusy(true);
    setError(null);
    try {
      const {body} = await invokeTool(operation.requestJson, operation.key, {
        signal: controller.signal,
      });
      if (
        !mounted.current ||
        controller.signal.aborted ||
        currentAiCredential() !== credential
      )
        return;
      if (
        body.data.kind !== 'INVOCATION' ||
        typeof body.data.executionId !== 'string' ||
        !body.data.executionId ||
        body.data.executionId.length > 256 ||
        typeof body.data.acceptedAt !== 'string' ||
        !body.data.acceptedAt
      )
        throw new Error('回执无法确认，请继续确认原调用。');
      const accepted = {...operation, acceptedId: body.data.executionId};
      if (!save(accepted)) {
        // 已知回执即使无法落盘也保留在内存供用户复制；原命令仍在存储，可用原键确认。
        recordRef.current = accepted;
        setRecord(accepted);
      } else if (pendingRef.current.some(value => value.key === operation.key)) {
        // 已拿到回执并保存当前记录后，才移出待确认列表；失败时仍可按原键恢复。
        savePending(pendingRef.current.filter(value => value.key !== operation.key));
      }
    } catch (failure) {
      if (
        !mounted.current ||
        controller.signal.aborted ||
        currentAiCredential() !== credential
      )
        return;
      setError(toolInvocationFailure(failure, recovery ? 'confirmation' : 'submission'));
      // 恢复时的拒绝可能是受理后撤权，不能据此断言原调用不存在。
      if (
        !recovery &&
        failure instanceof AiApiError &&
        failure.httpStatus >= 400 &&
        failure.httpStatus < 500 &&
        failure.httpStatus !== 408 &&
        failure.code !== '205005' &&
        failure.code !== '205027'
      ) {
        if (!removeValue(target)) setBlocked(true);
        recordRef.current = null;
        setRecord(null);
      }
    } finally {
      if (active.current === controller) active.current = null;
      if (mounted.current && !controller.signal.aborted) setBusy(false);
    }
  };
  const acceptedId = record?.acceptedId;
  const {tenantId, workspaceId} = context.scope;
  useEffect(() => {
    setStatus(null);
    setResult(null);
    setReadError(null);
    if (!acceptedId) return;
    let disposed = false;
    let controller: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let failures = 0;
    let finished = false;
    const credential = currentAiCredential();
    const live = () => !disposed && currentAiCredential() === credential;
    const poll = async () => {
      if (!live() || document.hidden || controller || finished) return;
      const request = new AbortController();
      controller = request;
      try {
        const {body} = await getInvocationStatus(
          {scope: {tenantId, workspaceId}, invocationId: acceptedId},
          {signal: request.signal},
        );
        if (!live() || request.signal.aborted) return;
        if (body.data.kind !== 'TOOL' || body.data.invocationId !== acceptedId)
          throw new Error('读取到的调用类型或身份不一致。');
        setStatus(body.data);
        if (body.data.resultAvailable) {
          const resultResponse = await getInvocationResult(
            {scope: {tenantId, workspaceId}, invocationId: acceptedId},
            {signal: request.signal},
          );
          if (!live() || request.signal.aborted) return;
          const value = resultResponse.body.data as InvocationResultResponse & {
            outputJson?: string | null;
          };
          if (value.kind !== 'TOOL' || value.invocationId !== acceptedId)
            throw new Error('结果身份或类型不一致。');
          const tool = value.result.value as ToolResult;
          if (
            !tool ||
            typeof tool.callId !== 'string' ||
            !['SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(tool.outcome) ||
            !['NONE', 'POSSIBLE', 'CONFIRMED'].includes(tool.sideEffect) ||
            !Array.isArray(tool.sources) ||
            !Array.isArray(tool.artifacts) ||
            (value.outputJson != null && typeof value.outputJson !== 'string')
          )
            throw new Error('工具结果格式尚未确认，请重新读取原调用。');
          setResult({value: tool, outputJson: value.outputJson ?? null});
        }
        setReadError(null);
        failures = 0;
        finished = toolTerminal(body.data);
      } catch (failure) {
        if (!live() || request.signal.aborted) return;
        setReadError(toolFailure(failure));
        failures++;
        if (
          failure instanceof AiApiError &&
          [401, 403, 404].includes(failure.httpStatus)
        )
          finished = true;
      } finally {
        if (controller === request) controller = null;
        if (live() && !document.hidden && !finished)
          timer = setTimeout(
            () => void poll(),
            Math.min(15_000, 1000 * 2 ** Math.min(failures, 4)),
          );
      }
    };
    const visibility = () => {
      clearTimeout(timer);
      if (document.hidden) controller?.abort();
      else void poll();
    };
    document.addEventListener('visibilitychange', visibility);
    void poll();
    return () => {
      disposed = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [acceptedId, tenantId, workspaceId, refreshTick]);
  const cancel = async () => {
    const original = recordRef.current;
    if (!original?.acceptedId || active.current || blocked) return;
    const operation = {
      ...original,
      cancelKey: original.cancelKey ?? crypto.randomUUID(),
    };
    if (!save(operation)) return;
    const controller = new AbortController();
    const credential = currentAiCredential();
    active.current = controller;
    setBusy(true);
    setError(null);
    try {
      const {body} = await cancelToolInvocation(
        context.scope,
        original.acceptedId,
        operation.cancelKey,
        {signal: controller.signal},
      );
      if (
        !mounted.current ||
        controller.signal.aborted ||
        currentAiCredential() !== credential
      )
        return;
      setCancelReceipt(
        body.data.outcome === 'ACCEPTED'
          ? '停止请求已受理，正在确认最终状态。'
          : `停止回执：${body.data.outcome}`,
      );
      setRefreshTick((value) => value + 1);
    } catch (failure) {
      if (
        mounted.current &&
        !controller.signal.aborted &&
        currentAiCredential() === credential
      )
        setError(toolInvocationFailure(failure));
    } finally {
      if (active.current === controller) active.current = null;
      if (mounted.current && !controller.signal.aborted) setBusy(false);
    }
  };
  const refresh = useCallback(() => setRefreshTick((value) => value + 1), []);
  return {
    record,
    history,
    pending,
    blocked,
    busy,
    error,
    status,
    result,
    readError,
    cancelReceipt,
    refresh,
    start: (requestJson: string) => {
      if (recordRef.current || active.current || blocked) return;
      const operation = {key: crypto.randomUUID(), requestJson};
      if (save(operation)) void execute(operation, false);
    },
    retry: () =>
      recordRef.current && !recordRef.current.acceptedId
        ? void execute(recordRef.current, true)
        : undefined,
    parkPending: () => {
      const original = recordRef.current;
      if (!original || original.acceptedId || active.current || blocked) return false;
      const next = [original, ...pendingRef.current.filter(value => value.key !== original.key)];
      if (next.length > 20) {
        setError('待确认调用已达 20 条，请先恢复并确认已有提交；不会丢弃旧记录。');
        return false;
      }
      // 先保存完整原命令，再释放当前槽位；不把暂存当成服务端取消或执行失败。
      return savePending(next) && releaseCurrent();
    },
    restorePending: (key: string) => {
      if (recordRef.current || active.current || blocked) return;
      const original = pendingRef.current.find(value => value.key === key);
      if (original && save(original)) {
        setError(null);
        // 恢复只展示原提交，不自动发送；拿到回执前仍保留暂存副本。
      }
    },
    cancel: () => void cancel(),
    clear: (acknowledgeUnknown = false) => {
      const original = recordRef.current;
      if (
        (!original?.historyOnly &&
          (!toolTerminal(status) ||
            (status?.state === 'UNKNOWN' && !acknowledgeUnknown))) ||
        active.current ||
        blocked
      )
        return;
      if (original?.acceptedId) {
        const next = [
          original.acceptedId,
          ...history.filter((id) => id !== original.acceptedId),
        ].slice(0, 20);
        if (!writeValue(historyTarget, next)) {
          setError('无法保存原调用编号，请先复制编号并检查浏览器存储。');
          return;
        }
        setHistory(next);
      }
      releaseCurrent();
    },
    open: (invocationId: string) => {
      if (
        recordRef.current ||
        active.current ||
        blocked ||
        !invocationId.trim() ||
        invocationId.length > 256
      )
        return;
      save({
        key: crypto.randomUUID(),
        requestJson: JSON.stringify({
          scope: context.scope,
          input: {bindingId: 'history'},
          expectedPlanDigest: '0'.repeat(64),
        }),
        acceptedId: invocationId.trim(),
        historyOnly: true,
      });
    },
  };
}
