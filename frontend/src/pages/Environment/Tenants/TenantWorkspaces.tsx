import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history} from '@umijs/max';
import {Alert, Button, Card, Flex, Typography} from 'antd';
import {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';
import {workspaceConfigurationLocation} from '@/features/configuration/configurationLocation';
import {retainWorkspaceSelection} from '@/features/workspace/useWorkspace';
import {browseTenantWorkspaces, type WorkspaceSummary} from '@/services/platform/workspace';
import {useConfigurationDirectory} from '@/features/configuration/useConfigurationDirectory';

const workspaceKey = (space: WorkspaceSummary) => space.scope.workspaceId;
const roles = ['负责人', '管理员', '成员', '访客'];
const states = ['正常', '已归档', '关闭处理中', '已删除'];

export default function TenantWorkspaces({userId, tenantId}: { userId: string; tenantId: string }) {
  const fetchPage = useCallback(async (cursor: string | null, signal: AbortSignal) =>
    (await browseTenantWorkspaces(tenantId, cursor, '', {signal})).body.data, [tenantId]);
  const directory = useConfigurationDirectory(fetchPage, workspaceKey);
  const navigation = useConfigurationNavigation();
  const selection = useRef<ReturnType<typeof retainWorkspaceSelection> | null>(null);
  const live = useRef(true);
  const [entering, setEntering] = useState<string | null>(null);
  const [result, setResult] = useState<{ success: boolean; text: string } | null>(null);
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      selection.current?.release();
      selection.current = null;
    };
  }, []);

  function enter(space: WorkspaceSummary) {
    navigation.request(() => {
      if (!live.current) return;
      selection.current ??= retainWorkspaceSelection(userId);
      setEntering(space.scope.workspaceId);
      setResult(null);
      void selection.current.select(space.scope, () => {
        if (live.current) setResult({success: true, text: `已进入「${space.name}」，聊天使用空间已切换。`});
      }).then(entered => {
        if (!entered && live.current) setResult(current => current ?? {
          success: false, text: '未切换使用空间。请检查访问资格或处理待确认的空间操作后重试。'
        });
      }).finally(() => {
        if (live.current) setEntering(null);
      });
    });
  }

  return <Flex vertical gap={12}>
    {result && <Alert type={result.success ? 'success' : 'warning'} title={result.text}/>}
    {directory.error && <Alert type="warning" title={directory.error}
                               action={<Button onClick={directory.reload}>重新读取</Button>}/>}
    {!directory.records.length && !directory.error && <Typography.Paragraph type="secondary">
      {directory.loading ? '正在读取工作空间…' : directory.exhausted
        ? '此租户暂无你可访问的工作空间。' : '本页暂无可访问的空间，可以继续加载。'}
    </Typography.Paragraph>}
    {directory.records.map(space => <Card key={workspaceKey(space)} size="small">
      <Flex justify="space-between" align="center" gap={12} wrap>
        <Flex vertical gap={4}>
          <Typography.Text strong>{space.name}</Typography.Text>
          <Typography.Text type="secondary">{roles[space.currentRole]} · {states[space.state]}</Typography.Text>
        </Flex>
        <Flex gap={8} wrap>
          <Button aria-label={`配置 ${space.name}`} onClick={() => navigation.request(() =>
            history.push(workspaceConfigurationLocation(userId, space.scope, 'overview')))}>配置</Button>
          <Button type="primary" aria-label={`进入此空间 ${space.name}`} loading={entering === workspaceKey(space)}
                  disabled={entering !== null}
                  onClick={() => enter(space)}>进入此空间</Button>
        </Flex>
      </Flex>
    </Card>)}
    {directory.nextCursor && !directory.expired && <Button loading={directory.loading}
                                                           onClick={directory.more}>继续加载空间</Button>}
  </Flex>;
}
