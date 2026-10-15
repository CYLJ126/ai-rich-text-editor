import React, {useEffect, useState} from 'react';
import {Alert, Button} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {ensureWorkspaceAi} from '@/services/platform/ai';
import type {WorkspaceScope} from '@/services/platform/workspace';
import type {BrowserContext} from './storage';
import WorkspaceAiSettings from './WorkspaceAiSettings';

/** 空间准备完成后才挂载聊天请求，避免新空间的授权和预算创建与首次发现竞争。 */
export default function WorkspaceAiPreparation({
                                                 scope,
                                                 context,
                                                 legacy,
                                                 children,
                                                 refreshRevision = 0,
                                                 onUnsafeChange,
                                                 fallback
                                               }: {
  scope: WorkspaceScope;
  context: BrowserContext;
  legacy: boolean;
  children: React.ReactNode;
  refreshRevision?: number;
  onUnsafeChange?: (unsafe: boolean) => void;
  fallback?: React.ReactNode;
}) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<'loading' | 'ready' | 'import' | 'error'>(legacy ? 'ready' : 'loading');
  const [error, setError] = useState('');
  useEffect(() => {
    if (state === 'ready') onUnsafeChange?.(false);
  }, [state, onUnsafeChange]);
  useEffect(() => {
    if (legacy) return;
    const controller = new AbortController();
    if (state !== 'ready') setState('loading');
    ensureWorkspaceAi(scope, {signal: controller.signal}).then(({body}) => {
      if (!controller.signal.aborted) setState(body.data.needsImport ? 'import' : 'ready');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      // FIXED deployments have no database control endpoint. Their existing authorization remains authoritative.
      if (failure instanceof AiApiError && failure.httpStatus === 404) setState('ready');
      else {
        setError(failure instanceof AiApiError && failure.httpStatus === 403
          ? '此空间暂不可用，请联系空间负责人或平台管理员确认访问权限与 AI 配置。'
          : failure instanceof AiApiError && failure.httpStatus === 401 ? '登录已失效，请重新登录。'
            : '空间 AI 配置暂时无法准备，请稍后重试。');
        setState('error');
      }
    });
    return () => controller.abort();
  }, [scope, legacy, revision, refreshRevision]);
  if (state === 'ready') return <>{children}</>;
  const status = state === 'loading' ? <p role="status">正在准备空间 AI 配置…</p>
    : state === 'import' ? <WorkspaceAiSettings scope={scope} context={context} onUnsafeChange={onUnsafeChange}
                                                onUpdated={() => setRevision(value => value + 1)}/>
      : <Alert type="info" title={error}
               action={<Button onClick={() => setRevision(value => value + 1)}>重新检查</Button>}/>;
  return <div className="ainew-bootstrap">{status}{fallback}</div>;
}
