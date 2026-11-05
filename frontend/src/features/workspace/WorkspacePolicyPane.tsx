import React, {useEffect, useState} from 'react';
import {history} from '@umijs/max';
import {Alert, Button, Card, Flex, Typography} from 'antd';
import {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';
import {AiApiError} from '@/services/arte-ai/request';
import {getPolicy, type PolicyField, type TenantPolicyView} from '@/services/platform/policy';
import type {WorkspaceView} from '@/services/platform/workspace';
import PolicyEditor from './PolicyEditor';
import type {WorkspaceVersionChange} from './workspaceVersionChange';

export default function WorkspacePolicyPane({userId, view, revision, versionChange, focus, onSaved, onUnsafeChange}: {
  focus?: { field: PolicyField };
  userId: string; view: WorkspaceView; revision: number; versionChange?: WorkspaceVersionChange;
  onSaved: (change?: WorkspaceVersionChange) => void; onUnsafeChange: (unsafe: boolean) => void;
}) {
  const {tenantId} = view.scope;
  const navigation = useConfigurationNavigation();
  const [ceiling, setCeiling] = useState<TenantPolicyView | null>(null);
  const [status, setStatus] = useState<'loading' | 'allowed' | 'denied' | 'error'>('loading');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const request = new AbortController();
    setStatus('loading');
    setCeiling(null);
    void getPolicy('tenant', {tenantId}, {signal: request.signal}).then(result => {
      if (request.signal.aborted || result.kind !== 'tenant') return;
      setCeiling(result.value);
      setStatus('allowed');
    }).catch(error => {
      if (!request.signal.aborted) setStatus(error instanceof AiApiError && [403, 404].includes(error.httpStatus)
        ? 'denied' : 'error');
    });
    return () => request.abort();
  }, [tenantId, revision, retry]);

  return <Flex vertical gap={12}>
    <Card title="租户政策上限" size="small">
      {status === 'loading' && <Typography.Text type="secondary">正在读取可查看的租户上限…</Typography.Text>}
      {status === 'denied' && <Typography.Text type="secondary">
        你当前无权查看完整租户政策。当前政策效果只显示本空间可核验的约束。
      </Typography.Text>}
      {status === 'error' && <Alert type="warning" title="租户上限暂时无法读取。"
                                    action={<Button onClick={() => setRetry(value => value + 1)}>重试</Button>}/>}
      {ceiling && <Flex vertical gap={8} align="start">
        <Typography.Text>{ceiling.tenantName} · 政策版本 {ceiling.policy.version}</Typography.Text>
        <Flex gap={8} wrap>
          <Button onClick={() => setRetry(value => value + 1)}>重新读取租户上限</Button>
          <Button onClick={() => navigation.request(() => history.push(
            `/Environment/Tenants?${new URLSearchParams({account: userId, tenantId, tab: 'policy'})}`
          ))}>查看租户政策</Button>
        </Flex>
      </Flex>}
    </Card>
    <PolicyEditor userId={userId} scope={view.scope} kind="workspace" workspaceName={view.name}
                  ceiling={ceiling} focus={focus} versionChange={versionChange} refreshRevision={revision}
                  onSaved={onSaved}
                  onUnsafeChange={onUnsafeChange}/>
  </Flex>;
}
