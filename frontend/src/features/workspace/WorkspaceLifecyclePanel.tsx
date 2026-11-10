import React, {useEffect, useState} from 'react';
import {Alert, Button, Card, Flex, Input, Popconfirm, Typography} from 'antd';
import {
  type ClosurePreview,
  getLifecycleCapabilities,
  type LifecycleCapabilities,
  previewClosure
} from '@/services/platform/workspaceLifecycle';
import type {WorkspaceView} from '@/services/platform/workspace';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {useLifecycleSession} from './LifecycleSession';

export const closureReason = (code: string) => ({
  EXECUTION_UNKNOWN: '执行结果未知，需取得证据后核对',
  EXECUTION_ACTIVE: '仍有未完成执行',
  BUDGET_HELD: '账户仍有未结预留',
  RESERVATION_UNSETTLED: '仍有待核对费用',
  TOOL_RUN_ACTIVE: '仍有未完成工具会话',
  TOOL_BUDGET_HELD: '工具会话仍有未结费用',
  TENANT_MODEL_DEPENDENCY: '此空间仍管理未停用的租户模型，请先停用相关模型',
  AI_SEPARATE_DATASOURCE: '独立 AI 数据源尚不能提供同事务关闭检查',
  AI_SCHEMA_UNAVAILABLE: '无法确认 AI 数据表完整性',
  AI_DATASOURCE_UNAVAILABLE: 'AI 数据源不可用',
  AUTHORITY_UNAVAILABLE: '领域检查暂时不可用',
  PARTICIPANT_UNAVAILABLE: '必要领域未就绪',
  SCAN_LIMIT_REACHED: '检查候选超过本批上限，需进一步治理检查',
  TOOL_BUDGET_SCHEMA_UNAVAILABLE: '无法确认工具会话账本',
  ORPHAN_RESERVATION: '发现无法关联账户的预留，需治理核对'
} as Record<string, string>)[code] ?? '存在尚未确认的领域依赖';

export default function WorkspaceLifecyclePanel({view, disabled, onUpdated, onClosed}: {
  view: WorkspaceView; disabled?: boolean; onUpdated(): void; onClosed(): void;
}) {
  const session = useLifecycleSession();
  const [caps, setCaps] = useState<LifecycleCapabilities>();
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState<ClosurePreview>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const enabled = !!session;
  useConfigurationDraft({
    group: 'workspace-lifecycle-reason',
    label: '生命周期操作说明',
    status: reason ? 'dirty' : 'clean',
    discard: () => {
      setReason('');
      setPreview(undefined);
      return true;
    }
  });
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    setCaps(undefined);
    setPreview(undefined);
    void getLifecycleCapabilities(view.scope, {signal: controller.signal}).then(value => {
      if (!controller.signal.aborted) setCaps(value);
    })
      .catch(() => {
        if (!controller.signal.aborted) setError('生命周期资格读取失败，请重新读取。');
      });
    return () => controller.abort();
  }, [enabled, view.scope.tenantId, view.scope.workspaceId, view.version, session?.revision, retry]);
  const locked = disabled || session?.locked || loading;

  async function act(action: 'archive' | 'restore' | 'close') {
    const result = await session?.run({
      action,
      scope: view.scope,
      expectedWorkspaceVersion: view.version,
      reason,
      name: view.name
    });
    if (result) {
      setReason('');
      setPreview(undefined);
      if (action === 'close') onClosed(); else onUpdated();
    }
  }

  if (!session) return null;
  return <Card title="空间生命周期" size="small">
    <Flex vertical gap={10}>
      {view.state === 1 && <Alert type="info"
                                  title="空间已归档：历史按权限可读，不能新增、编辑、扩大共享或发起新执行。已有任务继续按原规则处理。"/>}
      {error && <Alert type="warning" title={error} action={<Button onClick={() => {
        setError('');
        setRetry(value => value + 1);
      }}>重新读取生命周期</Button>}/>}
      {caps?.defaultWorkspace &&
        <Typography.Text type="secondary">系统默认个人空间受保护，不能归档或关闭。</Typography.Text>}
      {caps && !caps.closureInstalled && !caps.defaultWorkspace &&
        <Typography.Text type="secondary">关闭功能需先安装生命周期表；归档仍可按权限操作。</Typography.Text>}
      {caps && (caps.archive || caps.restore || caps.close) && <>
        <Input.TextArea aria-label="生命周期操作说明" placeholder="操作说明（可选）" value={reason} maxLength={1000}
                        disabled={locked} onChange={event => setReason(event.target.value)}/>
        <Flex gap={8} wrap>
          {caps.archive &&
            <Popconfirm title="归档此空间？" description="停止新增普通业务；已有任务不会自动取消。" okText="确认归档"
                        onConfirm={() => act('archive')}>
              <Button disabled={locked}>归档空间</Button>
            </Popconfirm>}
          {caps.restore && <Popconfirm title="取消归档并恢复使用？" okText="确认恢复" onConfirm={() => act('restore')}>
            <Button disabled={locked}>取消归档</Button>
          </Popconfirm>}
          {caps.close && <Button danger loading={loading} disabled={locked} onClick={async () => {
            setLoading(true);
            setError('');
            try {
              setPreview(await previewClosure(view.scope));
            } catch {
              setError('关闭预检失败，请重新读取后重试。');
            } finally {
              setLoading(false);
            }
          }}>关闭预检</Button>}
        </Flex>
        {preview && <Card size="small" title="关闭影响确认">
          <Flex vertical gap={8}>
            <Typography.Text>关闭受理后停止普通访问，进入关闭记录；本版不支持取消关闭或删除恢复。完成检查后仅作软删除，资源、账本与审计保留。</Typography.Text>
            {Object.entries(preview.retainedResources).map(([key, count]) => <Typography.Text key={key}>{({
              model: '模型',
              assistant: '助手',
              article: '文章',
              collection: '集合',
              accounts: '预算账户',
              invocations: '执行记录',
              toolRuns: '工具会话'
            } as Record<string, string>)[key.split(':').at(-1) ?? ''] ?? '资源记录'}：{count} 项保留</Typography.Text>)}
            {preview.blockers.map((item, index) => <Alert key={`${item.domain}:${index}`} type="warning"
                                                          title={closureReason(item.code)}/>)}
            {preview.workspaceVersion !== view.version &&
              <Alert type="warning" title="空间版本已变化，请重新读取详情后预检。"/>}
            <Popconfirm title="确认申请关闭此空间？" description="关闭不可取消；阻塞项未解决时会一直保留关闭处理中。"
                        okText="确认申请关闭" onConfirm={() => act('close')}>
              <Button danger
                      disabled={locked || preview.workspaceVersion !== view.version || preview.blockers.some(item => !item.retryable)}>申请关闭空间</Button>
            </Popconfirm>
          </Flex>
        </Card>}
      </>}
    </Flex>
  </Card>;
}
