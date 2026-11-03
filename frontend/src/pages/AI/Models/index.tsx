import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Card, Flex, Modal, Select, Tabs, Typography} from 'antd';
import ModelManager from '@/features/ainew/ModelManager';
import ConnectionManager from '@/features/ainew/ConnectionManager';
import ModelPlanManager from '@/features/ainew/ModelPlanManager';
import DefaultAiTemplateEditor from '@/features/ainew/DefaultAiTemplateEditor';
import {registerWorkspaceGuard, useWorkspaceDirectory} from '@/features/workspace/useWorkspace';
import {getWorkspace, type WorkspaceScope, type WorkspaceView} from '@/services/platform/workspace';
import type {ModelSelection} from '@/services/platform/ai';
import {sameScope} from '@/features/ainew/storage';
import {aiLocation, parseAiIntent, useAiLeaveGuard, useAiRoute} from '@/features/ainew/aiNavigation';
import '@/features/ainew/personalModels.css';

export type ModelConfigurationTab = 'models' | 'sharing' | 'connections' | 'plans';
const scopeKey = (scope: WorkspaceScope) => JSON.stringify([scope.tenantId, scope.workspaceId]);
const modelUpdated = () => {
};

/** 模型管理空间为局部上下文，平台页签和聊天空间各自独立。 */
export default function ModelsPage({initialTab = 'models'}: { initialTab?: ModelConfigurationTab }) {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理模型。"/>;
  return <ModelConfiguration key={userId} userId={userId} initialTab={initialTab}
                             hasWorkspaceMenu={initialState?.currentUser?.menus?.includes('EnvironmentWorkspaces') === true}/>;
}

function ModelConfiguration({userId, initialTab, hasWorkspaceMenu}: {
  userId: string; initialTab: ModelConfigurationTab; hasWorkspaceMenu: boolean
}) {
  const directory = useWorkspaceDirectory(userId);
  const search = useAiRoute();
  const route = useMemo(() => {
    try {
      return {intent: parseAiIntent(search, userId), error: ''};
    } catch (failure) {
      return {intent: null, error: (failure as Error).message};
    }
  }, [search, userId]);
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [scopeError, setScopeError] = useState('');
  const [loadingScope, setLoadingScope] = useState(false);
  const requestedTab = new URLSearchParams(search).get('tab');
  const [tab, setTab] = useState<ModelConfigurationTab>(initialTab === 'sharing' ? 'models' : initialTab);
  useEffect(() => {
    if (requestedTab === 'models' || requestedTab === 'connections' || requestedTab === 'plans') setTab(requestedTab);
  }, [requestedTab]);
  const [modelUnsafe, setModelUnsafe] = useState(false);
  const [connectionUnsafe, setConnectionUnsafe] = useState(false);
  const [planUnsafe, setPlanUnsafe] = useState(false);
  const [templateUnsafe, setTemplateUnsafe] = useState(false);
  const [guardMessage, setGuardMessage] = useState('');
  const [targetModel, setTargetModel] = useState<ModelSelection | null>(null);
  const [targetScope, setTargetScope] = useState<string>();
  const unsafe = modelUnsafe || connectionUnsafe || planUnsafe || templateUnsafe;
  const blocked = useCallback(() => setGuardMessage('有未保存内容、临时密钥或待确认操作，请先保存、放弃或确认原操作。'), []);
  useAiLeaveGuard(unsafe, blocked);
  useEffect(() => {
    if (!unsafe) setGuardMessage('');
  }, [unsafe]);
  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (unsafe) blocked(); else proceed();
  }), [userId, unsafe, blocked]);
  const intent = route.intent;
  const oldSpaceLink = new URLSearchParams(search).get('tab') === 'workspace-ai';
  useEffect(() => {
    if (oldSpaceLink && intent && !unsafe) history.replace(aiLocation('workspaces', userId, intent));
  }, [oldSpaceLink, intent, unsafe, userId]);
  useEffect(() => {
    if (!intent || oldSpaceLink) return;
    const scope = intent.scope;
    if (!scope) return;
    const controller = new AbortController();
    setWorkspace(null);
    setLoadingScope(true);
    setScopeError('');
    getWorkspace(scope, {signal: controller.signal}).then(({body}) => {
      if (controller.signal.aborted) return;
      if (scopeKey(body.data.scope) !== scopeKey(scope)) throw new Error('管理空间响应不一致。');
      setWorkspace(body.data);
    }).catch(() => {
      if (!controller.signal.aborted) setScopeError('指定管理空间不可读或已失效，请重新选择。');
    })
      .finally(() => {
        if (!controller.signal.aborted) setLoadingScope(false);
      });
    return () => controller.abort();
  }, [intent, oldSpaceLink]);
  useEffect(() => {
    if (!workspace && !intent?.scope && !route.error && !oldSpaceLink && directory.current) setWorkspace(directory.current);
  }, [directory.current, workspace, intent, route.error, oldSpaceLink]);
  const context = useMemo(() => workspace ? {userId, scope: workspace.scope} : null,
    [userId, workspace?.scope.tenantId, workspace?.scope.workspaceId]);
  const choices = new Map<string, WorkspaceView>();
  for (const item of directory.records) choices.set(scopeKey(item.scope), item);
  if (workspace) choices.set(scopeKey(workspace.scope), workspace);
  const originalChatChanged = !!intent?.chat && !sameScope(intent.chat, directory.current?.scope);
  const localModel = workspace && intent?.scope && scopeKey(workspace.scope) === scopeKey(intent.scope) ? intent.model : undefined;
  const executionScope = workspace && intent?.scope && sameScope(workspace.scope, intent.scope) ? intent.execution : undefined;
  if (route.error) return <Alert type="warning" title={route.error}/>;
  if (oldSpaceLink) return <Alert type="info" title="空间 AI 配置已迁往工作空间配置。"/>;
  return <main className="personal-models-page" aria-label="空间模型管理页面">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <div><Typography.Title
        level={3}>模型管理</Typography.Title><Typography.Text>管理空间：{workspace?.name ?? '请选择'}</Typography.Text>
      </div>
      <Flex gap={8} wrap>
        <Select className="model-workspace-select" aria-label="选择工作空间" placeholder="选择管理空间"
                value={workspace ? scopeKey(workspace.scope) : undefined} disabled={modelUnsafe || loadingScope}
                loading={directory.loading || loadingScope} showSearch optionFilterProp="label"
                options={[...choices].map(([value, item]) => ({
                  value,
                  label: `${item.name}（${item.scope.tenantId} / ${item.scope.workspaceId}）`
                }))}
                onChange={key => {
                  if (!modelUnsafe) {
                    setWorkspace(choices.get(key) ?? null);
                    setScopeError('');
                  }
                }}
                popupRender={menu => <>{menu}{!directory.exhausted && directory.nextCursor &&
                  <Button onClick={() => void directory.loadMore()}>加载更多工作空间</Button>}
                  {directory.cursorExpired &&
                    <Button onClick={() => void directory.load('')}>重新加载工作空间</Button>}</>}/>
        <Button disabled={unsafe || originalChatChanged} onClick={() => history.push('/AI/ChatNew')}>返回聊天</Button>
        <Button disabled={unsafe || !hasWorkspaceMenu} onClick={() => history.push(aiLocation('workspaces', userId, {
          management: workspace?.scope, chat: intent?.chat ?? directory.current?.scope, returnTo: 'models'
        }))}>工作空间配置</Button>
      </Flex>
    </Flex>
    {originalChatChanged && <Typography.Paragraph
      type="secondary">原聊天空间已变化，请从工作空间明确打开所需聊天，避免返回错误范围。</Typography.Paragraph>}
    {!hasWorkspaceMenu && <Typography.Paragraph
      type="secondary">当前账号未分配工作空间配置菜单；请联系管理员分配现有菜单。模型管理仍可独立使用。</Typography.Paragraph>}
    {(!!scopeError || !!directory.error) &&
      <Alert type="warning" title={scopeError || '空间目录读取失败，可重新加载目录。'}/>}
    {unsafe && <Alert type="info" title="当前有未保存内容、临时密钥或待确认操作。切换页签保留内容，离开前请先处理。"/>}
    {guardMessage && <Alert type="warning" title={guardMessage}/>}
    <Tabs activeKey={tab} onChange={value => setTab(value as ModelConfigurationTab)} destroyOnHidden={false} items={[
      {
        key: 'connections',
        label: '审核连接',
        children: <ConnectionManager userId={userId} onUnsafeChange={setConnectionUnsafe}/>
      },
      {
        key: 'plans', label: '审核模型与费率', children: <Flex vertical gap={16}>
          <ModelPlanManager userId={userId} onUnsafeChange={setPlanUnsafe}/>
          <Card title="平台默认 AI 模板"><DefaultAiTemplateEditor
            context={{userId, scope: {tenantId: 'control-admin', workspaceId: 'default-template'}}}
            onUpdated={modelUpdated} onUnsafeChange={setTemplateUnsafe}/></Card></Flex>
      },
      {
        key: 'models', label: '模型管理', children: context ? <ModelManager context={context} onUpdated={modelUpdated}
                                                                            initialModel={localModel}
                                                                            executionScope={executionScope}
                                                                            authorization={initialTab === 'sharing' || intent?.view === 'authorization'}
                                                                            onUnsafeChange={setModelUnsafe}
                                                                            useInChatDisabled={unsafe || originalChatChanged}
                                                                            onUseInChat={sameScope(executionScope ?? context.scope, directory.current?.scope) && !originalChatChanged ? () => history.push('/AI/ChatNew') : undefined}
                                                                            onConfigureWorkspace={model => {
                                                                              setTargetModel(model);
                                                                              setTargetScope(undefined);
                                                                            }}/>
          : <Alert type="info" title="请选择可访问的管理空间，创建或维护个人／租户模型。"/>
      },
    ]}/>
    <Modal title="选择配置目标空间" open={!!targetModel} footer={null} onCancel={() => setTargetModel(null)}>
      <Typography.Paragraph>只定位所选固定版本；在工作空间页核验权限后，手动启用模型并维护预算规则。</Typography.Paragraph>
      {!hasWorkspaceMenu && <Alert type="info" title="当前账号缺少工作空间配置菜单；请联系管理员分配菜单。"/>}
      <Select aria-label="配置目标空间" style={{width: '100%'}} value={targetScope} placeholder="请选择同租户的执行空间"
              options={[...choices].filter(([, item]) => item.scope.tenantId === workspace?.scope.tenantId)
                .map(([value, item]) => ({value, label: `${item.name} · ${item.scope.workspaceId}`}))}
              onChange={setTargetScope}/>
      {!directory.exhausted && directory.nextCursor &&
        <Button onClick={() => void directory.loadMore()}>继续查找目标空间</Button>}
      <Button type="primary" disabled={unsafe || !hasWorkspaceMenu || !targetScope} onClick={() => {
        const target = targetScope && choices.get(targetScope);
        if (target && targetModel && !unsafe) history.push(aiLocation('workspaces', userId, {
          scope: target.scope, model: targetModel, management: workspace?.scope,
          chat: intent?.chat ?? directory.current?.scope, returnTo: 'models'
        }));
      }}>打开此空间 AI 配置</Button>
    </Modal>
  </main>;
}
