import {useEffect, useRef, useState} from 'react';
import {z} from 'zod';
import {AiApiError} from '@/services/arte-ai';
import {currentAiCredential} from '@/services/arte-ai/request';
import {type ToolBindingCommand, type ToolWritePath, writeToolBinding,} from '@/services/arte-ai/tool';
import {type BrowserContext, readValue, removeValue, storageKey, writeValue,} from '@/features/ainew/storage';

const pendingSchema = z.object({
  key: z.string().min(1).max(256),
  path: z.enum([
    'prepareDefaultBinding',
    'saveBinding',
    'setBindingState',
    'upgradeBinding',
  ]),
  requestJson: z.string().min(2).max(1_100_000),
});
export type ToolPendingCommand = z.infer<typeof pendingSchema>;

export function toolFailure(error: unknown): string {
  if (error instanceof AiApiError) {
    if (error.httpStatus === 401) return '登录已失效，请重新登录。';
    if (error.httpStatus === 403)
      return '当前空间或操作权限不足，请联系空间负责人确认 AI 使用资格和工具权限。';
    if (error.httpStatus === 404)
      return '工具服务未启用，或当前工具／配置已不可访问。';
    if (error.httpStatus === 409)
      return '配置或工具状态已变化，请重新读取并核对；您的输入仍保留。';
    if (error.httpStatus === 422)
      return '配置不符合工具要求，请核对字段、策略和支持范围。';
    return '工具服务暂不可用，请稍后确认原操作。';
  }
  return error instanceof Error && !(error instanceof TypeError)
    ? error.message
    : '网络连接中断，请确认原操作。';
}

/** 每个账号／空间保留一条未确认写命令；卸载只中止浏览器等待，不伪造服务端撤销。 */
export function useToolCommand(
  context: BrowserContext,
  onConfirmed: (receipt: ToolBindingCommand) => void,
) {
  const target = storageKey(context, 'tool-binding-command');
  const [initial] = useState(() => {
    const stored = readValue<unknown>(target);
    if (!stored.value) return {pending: null, blocked: !!stored.warning};
    const parsed = pendingSchema.safeParse(stored.value);
    try {
      if (!parsed.success) throw new Error('invalid');
      const body = JSON.parse(parsed.data.requestJson);
      if (
        body.scope?.tenantId !== context.scope.tenantId ||
        body.scope?.workspaceId !== context.scope.workspaceId
      )
        throw new Error('scope');
      return {pending: parsed.data, blocked: false};
    } catch {
      return {pending: null, blocked: true};
    }
  });
  const [pending, setPending] = useState<ToolPendingCommand | null>(
    initial.pending,
  );
  const [blocked, setBlocked] = useState(initial.blocked);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(
    initial.blocked
      ? '原操作恢复记录无法读取，请检查浏览器存储后刷新；暂不能创建新操作。'
      : null,
  );
  const active = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const pendingRef = useRef(pending);
  const confirmed = useRef(onConfirmed);
  confirmed.current = onConfirmed;
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      active.current?.abort();
    };
  }, []);
  const execute = async (operation: ToolPendingCommand) => {
    if (active.current || blocked) return;
    const controller = new AbortController();
    active.current = controller;
    const credential = currentAiCredential();
    setBusy(true);
    setError(null);
    try {
      const {body} = await writeToolBinding(
        operation.path,
        operation.requestJson,
        operation.key,
        {signal: controller.signal},
      );
      if (
        !mounted.current ||
        controller.signal.aborted ||
        currentAiCredential() !== credential
      )
        return;
      const receipt = body.data;
      if (
        !['APPLIED', 'REPLAYED'].includes(receipt.delivery) ||
        receipt.outcome !== 'APPLIED' ||
        receipt.rejection !== 'NONE' ||
        !receipt.bindingId ||
        !receipt.binding ||
        !/^[1-9][0-9]{0,18}$/.test(receipt.binding.version)
      )
        throw new Error('原操作回执尚未确认，请查询原操作。');
      if (!removeValue(target)) {
        setBlocked(true);
        setError('原操作已完成，但恢复记录无法清理，请检查浏览器存储后刷新。');
      }
      pendingRef.current = null;
      setPending(null);
      confirmed.current(receipt);
    } catch (failure) {
      if (
        !mounted.current ||
        controller.signal.aborted ||
        currentAiCredential() !== credential
      )
        return;
      setError(toolFailure(failure));
      // 只清理服务端明确拒绝；网络、5xx 或回执格式异常继续使用原输入／原键确认。
      if (
        failure instanceof AiApiError &&
        failure.httpStatus >= 400 &&
        failure.httpStatus < 500 &&
        failure.httpStatus !== 408 &&
        failure.code !== '205027'
      ) {
        if (!removeValue(target)) setBlocked(true);
        pendingRef.current = null;
        setPending(null);
      }
    } finally {
      if (active.current === controller) active.current = null;
      if (mounted.current && !controller.signal.aborted) setBusy(false);
    }
  };
  return {
    pending,
    blocked,
    busy,
    error,
    start: (path: ToolWritePath, requestJson: string) => {
      if (pendingRef.current || active.current || blocked) return;
      const operation = {path, requestJson, key: crypto.randomUUID()};
      if (!writeValue(target, operation)) {
        setError('浏览器无法保存恢复记录，尚未发送操作，请检查存储后重试。');
        return;
      }
      pendingRef.current = operation;
      setPending(operation);
      void execute(operation);
    },
    retry: () =>
      pendingRef.current ? void execute(pendingRef.current) : undefined,
  };
}
