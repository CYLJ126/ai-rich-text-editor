import React, {useEffect, useMemo, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Select, Tabs, Typography} from 'antd';
import PersonalModelManager from '@/features/ainew/PersonalModelManager';
import ConnectionManager from '@/features/ainew/ConnectionManager';
import ModelSharingManager from '@/features/ainew/ModelSharingManager';
import ModelPlanManager from '@/features/ainew/ModelPlanManager';
import {protectWorkspaceChange, registerWorkspaceGuard, useWorkspaceDirectory} from '@/features/workspace/useWorkspace';
import type {WorkspaceScope, WorkspaceView} from '@/services/platform/workspace';
import '@/features/ainew/personalModels.css';

export type ModelConfigurationTab = 'models' | 'sharing' | 'connections' | 'plans';
const scopeKey = (scope: WorkspaceScope) => JSON.stringify([scope.tenantId, scope.workspaceId]);
const modelUpdated = () => {
};

/** 四个页签复用原管理组件，账号变化时一并清理，平台配置不随空间变化重建。 */
export default function ModelsPage({initialTab = 'models'}: { initialTab?: ModelConfigurationTab }) {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理模型。"/>;
  return <ModelConfiguration key={userId} userId={userId} initialTab={initialTab}/>;
}

function ModelConfiguration({userId, initialTab}: { userId: string; initialTab: ModelConfigurationTab }) {
  const directory = useWorkspaceDirectory(userId);
  const workspace = directory.current;
  const context = useMemo(() => workspace ? {userId, scope: workspace.scope} : null,
    [userId, workspace?.scope.tenantId, workspace?.scope.workspaceId]);
  const [tab, setTab] = useState<ModelConfigurationTab>(initialTab);
  const [modelUnsafe, setModelUnsafe] = useState(false);
  const [sharingUnsafe, setSharingUnsafe] = useState(false);
  const [connectionUnsafe, setConnectionUnsafe] = useState(false);
  const [planUnsafe, setPlanUnsafe] = useState(false);
  const [guardMessage, setGuardMessage] = useState('');
  const workspaceUnsafe = modelUnsafe || sharingUnsafe;
  const unsafe = workspaceUnsafe || connectionUnsafe || planUnsafe;
  const selectingDisabled = workspaceUnsafe || directory.selecting || directory.saving || !!directory.pending
    || directory.recoveryBlocked || directory.unsafeToLeave;
  const leavingDisabled = unsafe || directory.selecting || directory.unsafeToLeave;

  useEffect(() => registerWorkspaceGuard(userId, (_scope, proceed) => {
    if (workspaceUnsafe) setGuardMessage('模型或授权配置有未保存内容、临时密钥或待确认操作，请先处理后再切换工作空间。');
    else {
      setGuardMessage('');
      proceed();
    }
  }), [userId, workspaceUnsafe]);
  useEffect(() => {
    if (!workspaceUnsafe) setGuardMessage('');
  }, [workspaceUnsafe]);

  const choices = new Map<string, WorkspaceView>();
  for (const item of directory.records) choices.set(scopeKey(item.scope), item);
  if (workspace) choices.set(scopeKey(workspace.scope), workspace);

  function manageWorkspaces() {
    if (!leavingDisabled) history.push('/Environment/Workspaces');
  }

  const chooseWorkspace = <Select
    className="model-workspace-select" aria-label="选择工作空间" placeholder="选择工作空间"
    value={workspace ? scopeKey(workspace.scope) : undefined}
    disabled={selectingDisabled} loading={directory.loading || directory.selecting}
    showSearch optionFilterProp="label"
    options={[...choices].map(([value, item]) => ({
      value,
      label: `${item.name}（${item.scope.tenantId} / ${item.scope.workspaceId}）`
    }))}
    onOpenChange={open => {
      if (open && directory.search && !selectingDisabled) void directory.setSearch('');
    }}
    onChange={key => {
      const target = choices.get(key);
      if (!target || selectingDisabled || key === (workspace && scopeKey(workspace.scope))) return;
      protectWorkspaceChange(userId, target.scope, () => {
        void directory.select(target.scope);
      });
    }}
    popupRender={menu => <>
      {menu}
      <Flex className="model-workspace-menu-actions" vertical gap={8}>
        {!directory.exhausted && !directory.cursorExpired && directory.nextCursor &&
          <Button loading={directory.loading} disabled={directory.loading} onClick={() => void directory.loadMore()}>
            加载更多工作空间
          </Button>}
        {(directory.error != null || directory.cursorExpired) &&
          <Button loading={directory.loading} onClick={() => void directory.load('')}>重新加载工作空间</Button>}
        <Button disabled={leavingDisabled} onClick={manageWorkspaces}>管理工作空间</Button>
      </Flex>
    </>}
  />;
  const noWorkspace = <Alert type="info" title="请选择可访问的工作空间；初始化和空间管理可从工作空间下拉框进入。"/>;

  return <main className="personal-models-page" aria-label="空间模型管理页面">
    <Flex component="header" justify="space-between" align="center" wrap gap={12}>
      <div>
        <Typography.Title level={3}>模型管理</Typography.Title>
        <Typography.Text>{workspace?.name ?? '尚未选择工作空间'}</Typography.Text>
      </div>
      <Flex gap={8} wrap>
        {chooseWorkspace}
        <Button disabled={leavingDisabled} onClick={() => {
          if (!leavingDisabled) history.push('/AI/ChatNew');
        }}>返回聊天</Button>
      </Flex>
    </Flex>
    {directory.error != null && <Alert type="warning" title={directory.error instanceof Error
      ? directory.error.message : '工作空间读取失败，请重新加载。'}/>}
    {directory.warning && <Alert type="warning" title={directory.warning}/>}
    {(directory.pending || directory.recoveryBlocked || directory.unsafeToLeave) &&
      <Alert type="warning" title="空间操作尚未确认或恢复记录不可用，请在工作空间配置中处理原操作。"/>}
    {unsafe &&
      <Alert type="info" title="当前有未保存内容、临时密钥或待确认操作。切换页签会保留内容，返回聊天前请先处理。"/>}
    {guardMessage && <Alert type="warning" title={guardMessage}/>}
    <Tabs activeKey={tab} onChange={value => setTab(value as ModelConfigurationTab)} destroyOnHidden={false} items={[
      {
        key: 'models', label: '模型管理', children: context
          ? <PersonalModelManager context={context} onUnsafeChange={setModelUnsafe} onUpdated={modelUpdated}
                                  useInChatDisabled={leavingDisabled} onUseInChat={() => {
            if (!leavingDisabled) history.push('/AI/ChatNew');
          }}/>
          : noWorkspace,
      },
      {
        key: 'sharing', label: '租户模型与授权', children: context
          ? <ModelSharingManager context={context} onUnsafeChange={setSharingUnsafe}/>
          : noWorkspace,
      },
      {
        key: 'connections', label: '审核连接', children: <>
          <Typography.Paragraph
            type="secondary">平台审核连接独立于当前工作空间，操作按平台配置资格检查。</Typography.Paragraph>
          <ConnectionManager userId={userId} onUnsafeChange={setConnectionUnsafe}/>
        </>,
      },
      {
        key: 'plans', label: '审核模型与费率', children: <>
          <Typography.Paragraph
            type="secondary">平台模型与费率方案独立于当前工作空间。发布后请在模型管理中重新读取所需的审核配置。</Typography.Paragraph>
          <ModelPlanManager userId={userId} onUnsafeChange={setPlanUnsafe}/>
        </>,
      },
    ]}/>
  </main>;
}
