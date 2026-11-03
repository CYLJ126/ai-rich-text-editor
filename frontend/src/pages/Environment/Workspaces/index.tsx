import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Card, Flex, Typography} from 'antd';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import {protectWorkspaceChange, registerWorkspaceGuard, useWorkspaceDirectory} from '@/features/workspace/useWorkspace';
import {getWorkspace, type WorkspaceView} from '@/services/platform/workspace';
import WorkspaceAiGovernanceEditor from '@/features/ainew/WorkspaceAiGovernanceEditor';
import {aiLocation, parseAiIntent, useAiLeaveGuard, useAiRoute, verifyAiModel} from '@/features/ainew/aiNavigation';
import {notifyAiConfiguration, subscribeAiConfiguration} from '@/features/ainew/aiConfigurationEvents';
import {sameScope} from '@/features/ainew/storage';
import '@/features/ainew/personalModels.css';

export default function WorkspaceConfigurationPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理工作空间。"/>;
  return <WorkspaceConfiguration key={userId} userId={userId}/>;
}

function WorkspaceConfiguration({userId}: { userId: string }) {
  const search = useAiRoute();
  const parsed = useMemo(() => {
    try {
      return {intent: parseAiIntent(search, userId), error: ''};
    } catch (failure) {
      return {intent: null, error: (failure as Error).message};
    }
  }, [search, userId]);
  const intent = parsed.intent;
  const directory = useWorkspaceDirectory(userId);
  const [target, setTarget] = useState<WorkspaceView | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [editorUnsafe, setEditorUnsafe] = useState(false);
  const [directoryUnsafe, setDirectoryUnsafe] = useState(false);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const ownNotification = useRef(false);
  const unsafe = editorUnsafe || directoryUnsafe;
  const blocked = useCallback(() => setError('请先保存或放弃修改、确认原操作，再离开或更换配置目标。'), []);
  useAiLeaveGuard(unsafe, blocked);
  useEffect(() => {
    if (!unsafe) setError(value => value === '请先保存或放弃修改、确认原操作，再离开或更换配置目标。' ? '' : value);
  }, [unsafe]);
  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (unsafe) blocked(); else proceed();
  }), [userId, unsafe, blocked]);
  useEffect(() => {
    setTarget(null);
    setError('');
    if (!intent?.scope) return;
    const controller = new AbortController();
    const scope = intent.scope;
    setLoading(true);
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId) {
        controller.abort();
        setTarget(null);
      }
    };
    window.addEventListener('arte-account-session-cleared', logout);
    const load = async () => {
      const {body} = await getWorkspace(scope, {signal: controller.signal});
      if (!sameScope(body.data.scope, intent.scope)) throw new Error('目标空间响应不一致。');
      if (intent.model) await verifyAiModel(scope, intent.model, controller.signal);
      if (!controller.signal.aborted) setTarget(body.data);
    };
    void load().catch(failure => {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '目标空间或精确版本不可访问，请重新选择。');
    })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [intent, userId]);
  const context = useMemo(() => target ? {userId, scope: target.scope} : null, [userId, target]);
  useEffect(() => target ? subscribeAiConfiguration(userId, target.scope, () => {
    if (ownNotification.current) return;
    setNotice('此空间 AI 配置已变化。请重新读取最新配置；未保存草稿、原操作与当前聊天不变。');
    setRevision(value => value + 1);
  }) : undefined, [userId, target]);
  const originalChatChanged = !!intent?.chat && !sameScope(intent.chat, directory.current?.scope);

  function configure(view: WorkspaceView) {
    if (unsafe) {
      blocked();
      return;
    }
    if (intent?.management && intent.management.tenantId !== view.scope.tenantId) {
      setError('请选择与来源模型相同租户的目标空间。');
      return;
    }
    history.push(aiLocation('workspaces', userId, {...intent, scope: view.scope}));
  }

  return <main className="personal-models-page" aria-label="工作空间配置">
    <Typography.Title level={3}>工作空间配置</Typography.Title>
    <Typography.Paragraph>“配置”只打开目标空间详情；目录进入／切换、收藏和启动偏好各自独立。</Typography.Paragraph>
    {parsed.error && <Alert type="warning" title={parsed.error}/>}
    {error && <Alert type="warning" title={error}/>}
    {notice && <Alert type="info" title={notice}/>}
    {unsafe && <Alert type="info" title="有未保存修改或待确认操作，请处理后再更换目标或离开。"/>}
    {intent && !intent.scope && <Alert type="info" title="请在目录点击“配置”选择目标空间；不会自动使用当前聊天空间。"/>}
    {loading && <Typography.Paragraph role="status">正在核验目标空间和精确模型版本…</Typography.Paragraph>}
    <Flex gap={12} wrap style={{alignItems: 'flex-start'}}>
      <div style={{flex: '1 1 280px', minWidth: 0}}>
        <WorkspacePicker userId={userId} presentation="inline" configurationDisabled={unsafe || !!parsed.error}
                         onUnsafeChange={setDirectoryUnsafe} onConfigure={configure}/>
      </div>
      {target && context &&
        <Card style={{flex: '2 1 440px', minWidth: 0, maxWidth: '100%'}} title={`配置目标：${target.name}`}>
          <Typography.Paragraph style={{overflowWrap: 'anywhere'}}>租户：{target.scope.tenantId} ·
            空间：{target.scope.workspaceId}</Typography.Paragraph>
          {intent?.model &&
            <Alert type="info" title={`已核验定位版本：${intent.model.binding.id}@${intent.model.binding.version}`}
                   description="此定位不会自动选择模型、批准预算或准备本人账户。请在下方手动保存。"/>}
          <WorkspaceAiGovernanceEditor key={JSON.stringify([userId, target.scope])} scope={target.scope}
                                       context={context}
                                       onUnsafeChange={setEditorUnsafe} refreshRevision={revision}
                                       onUpdated={() => {
                                         ownNotification.current = true;
                                         notifyAiConfiguration(userId, target.scope);
                                         ownNotification.current = false;
                                         setNotice('空间 AI 操作已保存；已通知模型与聊天目录重读，仍须手动应用。');
                                       }}/>
          <Flex gap={8} wrap style={{marginTop: 16}}>
            <Button disabled={unsafe} onClick={() => history.push(aiLocation('models', userId, {
              scope: intent?.management ?? target.scope, management: intent?.management, execution: target.scope,
              chat: intent?.chat, model: intent?.management ? intent.model : undefined
            }))}>返回模型管理</Button>
            <Button disabled={unsafe || originalChatChanged}
                    onClick={() => history.push('/AI/ChatNew')}>返回原聊天</Button>
            <Button disabled={unsafe || directory.selecting}
                    onClick={() => protectWorkspaceChange(userId, target.scope, () => {
                      void directory.select(target.scope, () => history.push('/AI/ChatNew'));
                    })}>在此空间打开聊天</Button>
          </Flex>
          {originalChatChanged && <Typography.Paragraph
            type="secondary">原聊天空间已变化，暂不返回错误空间；可明确选择“在此空间打开聊天”。</Typography.Paragraph>}
        </Card>}
    </Flex>
    {!target && <Button disabled={unsafe}
                        onClick={() => history.push(intent?.returnTo === 'chat' ? '/AI/ChatNew' : aiLocation('models', userId, {
                          scope: intent?.management, chat: intent?.chat
                        }))}>返回来源页面</Button>}
  </main>;
}
