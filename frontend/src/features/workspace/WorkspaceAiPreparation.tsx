import React, {useEffect, useState} from 'react';
import {Alert, Button, Flex, Spin, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {getWorkspaceAi} from '@/services/platform/ai';
import type {WorkspaceScope} from '@/services/platform/workspace';
import type {BrowserContext} from '@/features/ainew/storage';
import {history} from '@umijs/max';
import {aiLocation} from '@/features/ainew/aiNavigation';
import '../aiManagement.css';

/** 只读确认目标空间可访问，禁止页面读取自动创建配置或本人预算。 */
export default function WorkspaceAiPreparation({
                                                 scope,
                                                 context,
                                                 legacy,
                                                 children,
                                                 refreshRevision = 0,
                                                 onUnsafeChange,
                                                 fallback, onOpenModels
                                               }: {
  scope: WorkspaceScope;
  context: BrowserContext;
  legacy: boolean;
  children: React.ReactNode;
  refreshRevision?: number;
  onUnsafeChange?: (unsafe: boolean) => void;
  fallback?: React.ReactNode;
  onOpenModels?: () => void;
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
    getWorkspaceAi(scope, {signal: controller.signal}).then(({body}) => {
      if (!controller.signal.aborted) setState(body.data.needsImport ? 'import' : 'ready');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      // FIXED deployments have no database control endpoint. Their existing authorization remains authoritative.
      if (failure instanceof AiApiError && failure.httpStatus === 404) setState('ready');
      else {
        setError(failure instanceof AiApiError && failure.httpStatus === 403
          ? '此空间暂不可用，请联系空间负责人或平台管理员确认访问权限与 AI 配置。'
          : failure instanceof AiApiError && failure.httpStatus === 401 ? '登录已失效，请重新登录。'
            : '空间 AI 配置暂时无法读取，请稍后重试。');
        setState('error');
      }
    });
    return () => controller.abort();
  }, [scope, legacy, revision, refreshRevision]);
  if (state === 'ready') return <>{children}</>;
  const status = state === 'loading' ? <Flex align="center" gap={8} role="status">
      <Spin size="small"/><Typography.Text>正在读取空间 AI 配置…</Typography.Text>
    </Flex>
    : state === 'import' ? <Alert type="info" title="平台审核目录尚未准备，请联系平台配置管理员。"
                                  action={<Button
                                    onClick={onOpenModels ?? (() => history.push(aiLocation('models', context.userId, {})))}>打开模型配置</Button>}/>
      : <Alert type="info" title={error}
               action={<Button onClick={() => setRevision(value => value + 1)}>重新检查</Button>}/>;
  return <Flex vertical gap={12} className="ainew-bootstrap ai-preparation-panel">{status}{fallback}</Flex>;
}
