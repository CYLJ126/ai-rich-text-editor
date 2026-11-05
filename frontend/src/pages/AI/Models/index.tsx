import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Modal, Select, Tabs, Typography} from 'antd';
import MasterDetailLayout from '@/components/MasterDetailLayout';
import ViewportPage from '@/components/MasterDetailLayout/ViewportPage';
import ModelManager from '@/features/ainew/ModelManager';
import ConnectionManager from '@/features/ainew/ConnectionManager';
import ModelPlanManager from '@/features/ainew/ModelPlanManager';
import DefaultAiTemplateEditor from '@/features/ainew/DefaultAiTemplateEditor';
import {useCurrentWorkspace, useWorkspaceManagement} from '@/features/workspace/useWorkspace';
import {
  browseTenantWorkspaces,
  getWorkspace,
  type WorkspaceScope,
  type WorkspaceSummary,
  type WorkspaceView
} from '@/services/platform/workspace';
import {
  type ConfigurationCapabilities,
  getConfigurationCapabilities,
  type ModelSelection
} from '@/services/platform/ai';
import {sameScope} from '@/features/ainew/storage';
import {aiLocation, parseAiIntent, useAiRoute, useChatReturn, verifyAiModel} from '@/features/ainew/aiNavigation';
import {AiApiError} from '@/services/arte-ai/request';
import '@/features/ainew/personalModels.css';
import ConfigurationBoundary, {
  useConfigurationFallback,
  useConfigurationNavigation
} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import {useConfigurationDirectory} from '@/features/configuration/useConfigurationDirectory';

export type ModelConfigurationTab = 'models' | 'sharing' | 'connections' | 'plans';
const scopeKey = (scope: WorkspaceScope) => JSON.stringify([scope.tenantId, scope.workspaceId]);
const spaceKey = (space: WorkspaceSummary) => scopeKey(space.scope);
const modelUpdated = () => {
};

export default function ModelsPage({initialTab = 'models'}: { initialTab?: ModelConfigurationTab }) {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理模型。"/>;
  return <ConfigurationBoundary key={userId} userId={userId}>
    <ModelConfiguration userId={userId} initialTab={initialTab}
                        hasWorkspaceMenu={initialState?.currentUser?.menus?.includes('EnvironmentWorkspaces') === true}/>
  </ConfigurationBoundary>;
}

function ModelConfiguration({userId, initialTab, hasWorkspaceMenu}: {
  userId: string; initialTab: ModelConfigurationTab; hasWorkspaceMenu: boolean;
}) {
  const directory = useWorkspaceManagement(userId);
  const chat = useCurrentWorkspace(userId);
  const navigation = useConfigurationNavigation();
  const search = useAiRoute();
  const requestedTab = new URLSearchParams(search).get('tab');
  const oldSpaceLink = requestedTab === 'workspace-ai';
  const route = useMemo(() => {
    try {
      const intent = parseAiIntent(search, userId);
      if (!oldSpaceLink && requestedTab !== null && !['models', 'connections', 'plans', 'sharing'].includes(requestedTab))
        throw new Error('模型配置页签无效。');
      if (intent.returnTo === 'chat' && !intent.chat) throw new Error('返回聊天定位缺少原聊天空间。');
      if (intent.model && !intent.scope) throw new Error('缺少模型所属管理空间。');
      if (!oldSpaceLink && intent.management && !sameScope(intent.scope, intent.management))
        throw new Error('管理空间定位不一致。');
      if (!oldSpaceLink && intent.execution && (!intent.scope || !intent.model))
        throw new Error('执行空间定位缺少精确模型版本。');
      return {intent, error: ''};
    } catch (failure) {
      return {intent: null, error: failure instanceof Error ? failure.message : '模型定位无效。'};
    }
  }, [search, userId, oldSpaceLink, requestedTab]);
  const intent = route.intent;
  const chatReturn = useChatReturn(userId, chat?.scope, intent?.chat);
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [localIntent, setLocalIntent] = useState(true);
  const [closed, setClosed] = useState(false);
  const [scopeError, setScopeError] = useState('');
  const [loadingScope, setLoadingScope] = useState(false);
  const scopeRead = useRef<AbortController | null>(null);
  const [tab, setTab] = useState<ModelConfigurationTab>(initialTab === 'sharing' ? 'models' : initialTab);
  const [capabilities, setCapabilities] = useState<ConfigurationCapabilities | null>(null);
  const [capabilityError, setCapabilityError] = useState('');
  const [capabilityRevision, setCapabilityRevision] = useState(0);
  const [modelUnsafe, setModelUnsafe] = useState(false);
  const [connectionUnsafe, setConnectionUnsafe] = useState(false);
  const [planUnsafe, setPlanUnsafe] = useState(false);
  const [templateUnsafe, setTemplateUnsafe] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [targetModel, setTargetModel] = useState<{ model: ModelSelection; management: WorkspaceScope } | null>(null);
  const unsafe = modelUnsafe || connectionUnsafe || planUnsafe || templateUnsafe;
  useConfigurationFallback(modelUnsafe, 'models', '模型配置');
  useConfigurationFallback(connectionUnsafe, 'connections', '平台连接');
  useConfigurationFallback(planUnsafe, 'plans', '模型方案与费率');
  useConfigurationFallback(templateUnsafe, 'template', '平台默认模板');

  useEffect(() => {
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      scopeRead.current?.abort();
      setWorkspace(null);
      setClosed(true);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      scopeRead.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);

  useEffect(() => {
    if (closed) return;
    const read = new AbortController();
    setCapabilityError('');
    void getConfigurationCapabilities({signal: read.signal}).then(value => {
      if (!read.signal.aborted) setCapabilities(value);
    }).catch(failure => {
      if (read.signal.aborted) return;
      setCapabilities(null);
      setCapabilityError(failure instanceof AiApiError && failure.httpStatus === 404
        ? '当前部署未开放平台 AI 配置。' : '平台配置资格暂不可核验，请重试。');
    });
    return () => read.abort();
  }, [userId, capabilityRevision, closed]);

  useEffect(() => {
    if (requestedTab === 'connections' || requestedTab === 'plans' || requestedTab === 'models') setTab(requestedTab);
    else if (requestedTab === 'sharing') setTab('models');
  }, [requestedTab]);

  useEffect(() => {
    if (oldSpaceLink && intent && !unsafe && hasWorkspaceMenu)
      history.replace(aiLocation('workspaces', userId, intent));
  }, [oldSpaceLink, intent, unsafe, hasWorkspaceMenu, userId]);

  function readScope(scope: WorkspaceScope, model?: ModelSelection, execution?: WorkspaceScope) {
    scopeRead.current?.abort();
    const read = new AbortController();
    scopeRead.current = read;
    setWorkspace(null);
    setLoadingScope(true);
    setScopeError('');
    void (async () => {
      const {body} = await getWorkspace(scope, {signal: read.signal});
      if (!sameScope(body.data.scope, scope)) throw new Error('管理空间响应不一致。');
      if (model && execution) await verifyAiModel(execution, model, read.signal);
      if (!read.signal.aborted) setWorkspace(body.data);
    })().catch(() => {
      if (!read.signal.aborted) setScopeError(model
        ? '管理空间、执行空间或指定模型版本不可读或已失效，请重新选择。'
        : '指定管理空间不可读或已失效，请重新选择。');
    }).finally(() => {
      if (!read.signal.aborted) setLoadingScope(false);
    });
  }

  useEffect(() => {
    if (closed || oldSpaceLink || !intent) return;
    setLocalIntent(true);
    setTargetModel(null);
    if (intent.scope) readScope(intent.scope, intent.model, intent.execution);
    else {
      scopeRead.current?.abort();
      setWorkspace(null);
      setScopeError('');
      setLoadingScope(false);
    }
    return () => scopeRead.current?.abort();
  }, [intent, oldSpaceLink, closed]);

  const context = useMemo(() => workspace ? {userId, scope: workspace.scope} : null,
    [userId, workspace?.scope.tenantId, workspace?.scope.workspaceId]);
  const choices = new Map(directory.records.map(item => [scopeKey(item.scope), item as WorkspaceView]));
  if (workspace) choices.set(scopeKey(workspace.scope), workspace);
  const matchesIntent = localIntent && sameScope(workspace?.scope, intent?.scope);
  const exact = matchesIntent ? intent?.model : undefined;
  const execution = matchesIntent ? intent?.execution : undefined;
  const managementFilter = <Select aria-label="选择管理空间" placeholder="选择管理空间"
                                   value={workspace ? scopeKey(workspace.scope) : undefined}
                                   loading={directory.loading || loadingScope}
                                   showSearch optionFilterProp="label" style={{width: '100%'}}
                                   options={[...choices].map(([value, item]) => ({
                                     value,
                                     label: `${item.name}（${item.scope.tenantId} / ${item.scope.workspaceId}）`
                                   }))}
                                   onChange={key => {
                                     const next = choices.get(key);
                                     if (!next || sameScope(workspace?.scope, next.scope)) return;
                                     navigation.request(() => {
                                       setLocalIntent(false);
                                       setTargetModel(null);
                                       readScope(next.scope);
                                     }, ['models']);
                                   }}
                                   popupRender={menu => <>{menu}{directory.nextCursor && !directory.cursorExpired &&
                                     <Button onClick={() => void directory.loadMore()}>加载更多工作空间</Button>}
                                     {(directory.cursorExpired || directory.error != null) &&
                                       <Button onClick={() => void directory.load('')}>重新加载工作空间</Button>}</>}/>;

  const canConnections = capabilities?.available === true && capabilities.manageConnections;
  const canPlans = capabilities?.available === true && capabilities.managePlans;
  const active = (tab === 'connections' && canConnections) || (tab === 'plans' && canPlans) ? tab : 'models';
  const requestedPlatform = tab === 'connections' || tab === 'plans';
  const canTemplate = capabilities?.available === true && capabilities.manageDefaultTemplate;

  if (closed) return <Alert type="warning" title="账号会话已结束，请重新登录后打开模型配置。"/>;
  if (route.error) return <Alert type="warning" title={route.error}
                                 action={<Button
                                   onClick={() => history.replace('/Environment/Models')}>打开模型目录</Button>}/>;
  if (oldSpaceLink) return <Alert type="info" title={hasWorkspaceMenu
    ? '正在打开工作空间 AI 配置。' : '当前账号未分配工作空间配置菜单。'}/>;

  function openExecutionSpace(scope: WorkspaceScope, model: ModelSelection, management: WorkspaceScope) {
    navigation.request(() => history.push(aiLocation('workspaces', userId, {
      scope, execution: scope, model, management, chat: intent?.chat ?? chatReturn.scope, returnTo: 'models',
    })));
  }

  return <ViewportPage className="personal-models-page model-configuration-page" label="空间模型管理页面">
    {chatReturn.changed && <Alert type="info" title="原聊天空间已变化，请重新确认当前使用空间。"/>}
    {chatReturn.error && <Alert type="warning" title={chatReturn.error}/>}
    {capabilityError && <Alert type="warning" title={capabilityError}
                               action={<Button
                                 onClick={() => setCapabilityRevision(value => value + 1)}>重新核验平台资格</Button>}/>}
    {requestedPlatform && active === 'models' && !capabilityError && <Alert type="info" title={capabilities
      ? '当前账号没有该平台配置权限。' : '正在核验平台配置资格。'}/>}
    {unsafe && <Alert type="info" title="当前有未保存内容、临时密钥或待确认操作。切换页签保留内容，离开前请先处理。"/>}
    <ConfigurationTarget name={active === 'models' ? workspace?.name : '平台 AI 配置'}
                         scope={active === 'models' ? workspace?.scope : undefined}
                         purpose={active === 'models' ? '模型管理空间' : '平台级连接、方案与默认模板'}/>
    <Tabs tabBarExtraContent={<Button disabled={chatReturn.changed} loading={chatReturn.loading}
                                      onClick={chatReturn.back}>返回聊天</Button>}
          activeKey={active} onChange={value => setTab(value as ModelConfigurationTab)} destroyOnHidden={false} items={[
      {
        key: 'models', label: '模型管理', children: context ? <ModelManager context={context} onUpdated={modelUpdated}
                                                                            scopeFilter={managementFilter}
                                                                            managementName={workspace?.name}
                                                                            initialModel={exact}
                                                                            executionScope={execution}
                                                                            authorization={initialTab === 'sharing' || (matchesIntent && intent?.view === 'authorization')}
                                                                            onUnsafeChange={setModelUnsafe}
                                                                            useInChatDisabled={unsafe}
                                                                            onUseInChat={sameScope(execution ?? context.scope, chatReturn.scope) && !chatReturn.changed ? chatReturn.back : undefined}
                                                                            onConfigureWorkspace={hasWorkspaceMenu ? (model, scope) => {
                                                                              if (scope) openExecutionSpace(scope, model, context.scope);
                                                                              else setTargetModel({
                                                                                model,
                                                                                management: context.scope
                                                                              });
                                                                            } : undefined}/>
          : <MasterDetailLayout label="模型管理" actions={[]} items={[]} onSelect={() => {
          }} listHeader={managementFilter}
                                emptyText="请选择管理空间">
            <Alert type={scopeError ? 'warning' : 'info'}
                   title={scopeError || '选择可访问的管理空间后，可以创建或维护个人与租户模型。'}/>
          </MasterDetailLayout>,
      },
      ...(canConnections ? [{
        key: 'connections',
        label: '供应商连接',
        children: <ConnectionManager userId={userId} onUnsafeChange={setConnectionUnsafe}/>,
      }] : []),
      ...(canPlans ? [{
        key: 'plans', label: '模型方案与费率', children: <ModelPlanManager userId={userId}
                                                                           onUnsafeChange={setPlanUnsafe}
                                                                           onOpenTemplate={canTemplate ? () => setTemplateOpen(true) : undefined}/>,
      }] : []),
    ]}/>
    {canTemplate && <Modal title="平台默认 AI 模板" open={templateOpen} footer={null} width={800}
                           onCancel={() => navigation.request(() => setTemplateOpen(false), ['template'])}
                           styles={{body: {maxHeight: '70dvh', overflowY: 'auto'}}}>
      <DefaultAiTemplateEditor context={{userId, scope: {tenantId: 'control-admin', workspaceId: 'default-template'}}}
                               onUpdated={modelUpdated} onUnsafeChange={setTemplateUnsafe}/>
    </Modal>}
    {targetModel &&
      <ExecutionSpaceDialog tenantId={targetModel.management.tenantId} onClose={() => setTargetModel(null)}
                            onSelect={scope => openExecutionSpace(scope, targetModel.model, targetModel.management)}/>}
  </ViewportPage>;
}

function ExecutionSpaceDialog({tenantId, onSelect, onClose}: {
  tenantId: string; onSelect: (scope: WorkspaceScope) => void; onClose: () => void;
}) {
  const readPage = useCallback(async (cursor: string | null, signal: AbortSignal) =>
    (await browseTenantWorkspaces(tenantId, cursor, '', {signal})).body.data, [tenantId]);
  const directory = useConfigurationDirectory(readPage, spaceKey);
  const [selected, setSelected] = useState<string>();
  const target = directory.records.find(item => spaceKey(item) === selected);
  return <Modal title="选择执行空间" open footer={null} onCancel={onClose}>
    <Flex vertical gap={8}>
      <Typography.Paragraph>选择同租户的执行空间，在工作空间页核验此版本，并按需启用模型和维护预算规则。</Typography.Paragraph>
      <Select aria-label="配置目标空间" value={target ? selected : undefined} placeholder="请选择执行空间"
              loading={directory.loading} options={directory.records.map(item => ({
        value: spaceKey(item), label: `${item.name} · ${item.scope.workspaceId}`,
      }))} onChange={setSelected}/>
      {directory.error && <Alert type="warning" title={directory.error}
                                 action={<Button onClick={directory.reload}>重新读取</Button>}/>}
      {directory.nextCursor && !directory.expired && <Button onClick={directory.more}>继续查找目标空间</Button>}
      <Button type="primary" disabled={!target} onClick={() => target && onSelect(target.scope)}>打开此空间 AI
        配置</Button>
    </Flex>
  </Modal>;
}
