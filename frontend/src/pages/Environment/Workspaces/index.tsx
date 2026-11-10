import {workspaceOwnership, workspaceRelationship} from '@/features/workspace/workspaceIdentity';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Input, Modal, Tooltip, Typography} from 'antd';
import MasterDetailLayout from '@/components/MasterDetailLayout';
import ViewportPage from '@/components/MasterDetailLayout/ViewportPage';
import DynamicIcon from '@/components/DynamicIcon/MyDynamicIcon';
import WorkspaceDetailForm from '@/features/workspace/WorkspaceDetailForm';
import {retainWorkspaceSelection, useCurrentWorkspace, useWorkspaceManagement} from '@/features/workspace/useWorkspace';
import {browseAccessibleTenants, type TenantSummary} from '@/services/platform/tenant';
import {getWorkspace, type WorkspaceScope, type WorkspaceView} from '@/services/platform/workspace';
import ConfigurationBoundary, {
  useConfigurationFallback,
  useConfigurationNavigation
} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import {sameScope} from '@/features/ainew/storage';
import {
  type AiIntent,
  aiLocation,
  parseAiIntent,
  useAiRoute,
  useChatReturn,
  verifyAiModel
} from '@/features/ainew/aiNavigation';
import {getManagedModel} from '@/services/platform/model';
import type {AiModel} from '@/services/platform/ai';
import type {WorkspaceTab} from '@/features/configuration/configurationLocation';
import {configurationReadError, useConfigurationDirectory} from '@/features/configuration/useConfigurationDirectory';
import {MembershipSession, useMembershipSession} from '@/features/workspace/MembershipSession';
import {MembershipInbox} from '@/features/workspace/MembershipPanel';
import '@/features/ainew/personalModels.css';
import './index.css';

const keyOf = (scope: WorkspaceScope) => JSON.stringify([scope.tenantId, scope.workspaceId]);
const tabs = new Set(['overview', 'ai', 'policy', 'members']);
const tenantKey = (tenant: TenantSummary) => tenant.id;
type Location = { scope: WorkspaceScope; tab: WorkspaceTab; intent: AiIntent };
type PreferenceChange = Parameters<ReturnType<typeof useWorkspaceManagement>['updatePreference']>[0];

export default function WorkspaceConfigurationPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const search = useAiRoute();
  if (!userId) return <Alert type="info" title="请登录后管理工作空间。"/>;
  let location: Location | undefined;
  const tab = new URLSearchParams(search).get('tab');
  try {
    const intent = parseAiIntent(search, userId);
    if (tab !== null && !tabs.has(tab)) throw new Error('工作空间页签无效。');
    if (intent.model && (!intent.scope || !intent.management || tab !== 'ai'))
      throw new Error('模型定位需要完整的管理空间、执行空间和 AI 页签。');
    if (intent.execution && (!intent.model || !sameScope(intent.scope, intent.execution)))
      throw new Error('执行空间定位不一致。');
    if (intent.returnTo === 'chat' && !intent.chat)
      throw new Error('返回聊天定位缺少原聊天空间。');
    if (intent.scope) location = {scope: intent.scope, tab: (tab ?? 'overview') as WorkspaceTab, intent};
  } catch (failure) {
    return <Alert type="warning" title={failure instanceof Error ? failure.message : '工作空间定位无效，请重新选择。'}
                  action={<Button onClick={() => history.replace('/Environment/Workspaces')}>打开空间目录</Button>}/>;
  }
  return <ConfigurationBoundary key={userId} userId={userId}>
    <MembershipSession userId={userId}>
      <WorkspaceConfiguration key={location ? search : 'directory'} userId={userId} location={location}
                              defaultTab={(tab ?? 'overview') as WorkspaceTab}/>
    </MembershipSession>
  </ConfigurationBoundary>;
}

/** 显式配置链接经服务端核验后打开本页目标。 */
function WorkspaceConfiguration({userId, location, defaultTab}: {
  userId: string; location?: Location; defaultTab: WorkspaceTab;
}) {
  const directory = useWorkspaceManagement(userId);
  const membership = useMembershipSession();
  const membershipRevision = membership?.revision ?? 0;
  const loadDirectory = useRef(() => directory.load(directory.search));
  loadDirectory.current = () => directory.load(directory.search);
  useEffect(() => {
    if (membershipRevision) void loadDirectory.current();
  }, [membershipRevision]);
  const chat = useCurrentWorkspace(userId);
  const [task, setTask] = useState(location?.intent);
  const [focusedModel, setFocusedModel] = useState<AiModel>();
  const chatReturn = useChatReturn(userId, chat?.scope, task?.chat);
  const [target, setTarget] = useState<WorkspaceView | null>(null);
  const [locating, setLocating] = useState(!!location);
  const [locationError, setLocationError] = useState('');
  const [initialLocation] = useState(location);
  const [creating, setCreating] = useState(false);
  const [initialTenantId, setInitialTenantId] = useState('');
  const [filter, setFilter] = useState(directory.search);
  const [formUnsafe, setFormUnsafe] = useState(false);
  const [targetTab, setTargetTab] = useState<WorkspaceTab>(defaultTab);
  const [closed, setClosed] = useState(false);
  const [preferenceDialogOpen, setPreferenceDialogOpen] = useState(false);
  const alive = useRef(true);
  const unsafe = formUnsafe || directory.saving || !!directory.pending || directory.recoveryBlocked || directory.unsafeToLeave;
  const {preferenceSaving, preferenceFailure, preferencePending} = directory;
  const canRetryPreference = !!preferencePending && !directory.recoveryBlocked;
  const navigation = useConfigurationNavigation();
  useConfigurationFallback(directory.saving || !!directory.pending || directory.recoveryBlocked || directory.unsafeToLeave,
    'workspace-operation', '空间管理操作');

  useEffect(() => {
    setFilter(directory.search);
  }, [directory.search]);

  useEffect(() => {
    if (!preferenceSaving && (preferenceFailure || preferencePending)) setPreferenceDialogOpen(true);
  }, [preferenceSaving, preferenceFailure, preferencePending]);

  function changePreference(change: PreferenceChange) {
    if (preferenceSaving) return;
    if (preferencePending) setPreferenceDialogOpen(true);
    else void directory.updatePreference(change);
  }

  useEffect(() => {
    alive.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      alive.current = false;
      setTarget(null);
      setCreating(false);
      setClosed(true);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      alive.current = false;
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);

  useEffect(() => {
    if (!initialLocation) return;
    const request = new AbortController();
    const {scope, tab, intent} = initialLocation;
    const load = async () => {
      const {body} = await getWorkspace(scope, {signal: request.signal});
      if (!sameScope(body.data.scope, scope)) throw new Error('空间范围不一致。');
      if (intent.model && intent.management) {
        const [source, focused] = await Promise.all([
          getManagedModel(intent.management, intent.model.resourceId, {signal: request.signal}),
          verifyAiModel(scope, intent.model, request.signal),
        ]);
        if (source.body.data.state !== 'PUBLISHED') throw new Error('模型已不可用。');
        if (!request.signal.aborted && alive.current) setFocusedModel(focused);
      }
      if (!request.signal.aborted && alive.current) {
        setTarget(body.data);
        setTargetTab(tab);
      }
    };
    void load().catch(failure => {
      if (!request.signal.aborted && alive.current)
        setLocationError(configurationReadError(failure, intent.model
          ? '指定模型版本或空间不可读、已失效或不符合目标空间条件，请重新选择。'
          : '无法打开所选工作空间，请从目录重新选择。'));
    }).finally(() => {
      if (!request.signal.aborted && alive.current) setLocating(false);
    });
    return () => request.abort();
  }, [initialLocation]);

  function recover() {
    const operation = directory.pending;
    if (operation?.kind === 'rename') {
      const scope = operation.request.scope;
      if (sameScope(target?.scope, scope)) setTargetTab('overview');
      else navigation.request(() => {
        setCreating(false);
        setTargetTab('overview');
        setTask(undefined);
        setFocusedModel(undefined);
        setTarget({
          scope, name: operation.request.name, description: operation.request.description,
          version: operation.request.expectedVersion, state: 0, currentRole: 3
        });
      }, ['workspace-ai', 'space-policy']);
    } else if (operation?.kind === 'create') {
      if (creating) return;
      navigation.request(() => {
        setTarget(null);
        setInitialTenantId(operation.request.tenantId);
        setCreating(true);
      }, ['workspace-ai', 'space-policy']);
    } else void directory.retry();
  }

  if (closed) return <Alert type="warning" title="账号会话已结束，请重新登录后打开工作空间配置。"/>;
  return <ViewportPage className="personal-models-page workspace-configuration-page" label="工作空间配置">
    <ConfigurationTarget name={creating ? '新建工作空间' : target?.name} scope={target?.scope} purpose="工作空间配置"/>
    <div className="workspace-configuration-content">
      <MasterDetailLayout
        label="工作空间管理" actions={[]}
        selectedKey={!creating && target ? keyOf(target.scope) : undefined}
        onSelect={key => {
          if (!creating && target && keyOf(target.scope) === key) return;
          navigation.request(() => {
            const item = directory.records.find(value => keyOf(value.scope) === key);
            if (!item) return;
            setCreating(false);
            setLocationError('');
            setTask(undefined);
            setFocusedModel(undefined);
            setTargetTab(defaultTab);
            setTarget(item);
          });
        }}
        listHeader={<Flex gap={8} align="center" className="workspace-directory-actions">
          <Input.Search aria-label="搜索工作空间" placeholder="搜索工作空间" value={filter}
                        maxLength={100} allowClear
                        enterButton={<Button aria-label="搜索" title="搜索工作空间"
                                             icon={<DynamicIcon iconName="SearchOutlined"/>}/>}
                        loading={directory.loading} disabled={directory.saving}
                        onChange={event => setFilter(event.target.value)}
                        onSearch={value => void directory.load(value)}/>
          <Tooltip title="新建工作空间">
            <Button aria-label="新建工作空间"
                    icon={<DynamicIcon iconName="PlusOutlined"/>}
                    disabled={creating || directory.saving || locating}
                    onClick={() => navigation.request(() => {
                      setInitialTenantId(target?.scope.tenantId ?? '');
                      setTarget(null);
                      setTask(undefined);
                      setFocusedModel(undefined);
                      setLocationError('');
                      setCreating(true);
                    })}/>
          </Tooltip>
          <Tooltip title="清除启动空间偏好">
            <Button aria-label="清除启动空间偏好"
                    icon={<DynamicIcon iconName="ClearOutlined"/>}
                    aria-disabled={preferenceSaving || undefined}
                    disabled={!(canRetryPreference && !preferenceSaving) && (unsafe || !directory.preference?.startupScope)}
                    onClick={() => changePreference({startupScope: null})}/>
          </Tooltip>
        </Flex>}
        listFooter={<Flex vertical gap={8} style={{marginTop: 12}}>
          {directory.warning && <Alert type="warning" title={directory.warning}/>}
          {directory.error != null && <Alert type="warning"
                                             title={directory.error instanceof Error ? directory.error.message : '空间目录读取失败。'}/>}
          {directory.pending && <Alert type="warning" title="有待确认空间操作，请处理原操作。"
                                       action={<Button
                                         disabled={directory.saving || directory.recoveryBlocked}
                                         onClick={recover}>处理原空间操作</Button>}/>}
          {!directory.exhausted && directory.nextCursor && !directory.cursorExpired &&
            <Button loading={directory.loading} disabled={directory.loading || directory.saving}
                    onClick={() => void directory.loadMore()}>继续加载</Button>}
          {directory.cursorExpired &&
            <Button onClick={() => void directory.load(filter)}>重新加载目录</Button>}
          {directory.loaded && !directory.preference && !directory.error &&
            <Button disabled={unsafe || !!preferencePending}
                                                                                    onClick={() => void directory.initialize()}>初始化个人默认空间</Button>}
        </Flex>}
        emptyText={directory.loading ? '正在读取空间目录…' : '没有匹配的工作空间。'}
        items={directory.records.map(item => ({
          key: keyOf(item.scope), label: `配置 ${item.name}`, disabled: directory.saving || locating,
          actions: <WorkspacePreferenceActions workspace={item} directory={directory} onChange={changePreference}
                                               disabled={unsafe}/>,
          content: <Flex vertical gap={8}>
            <Typography.Text strong>{item.name}</Typography.Text>
            <Typography.Text type="secondary">{workspaceOwnership(item)}</Typography.Text>
            <Typography.Text
              type="secondary">{workspaceRelationship(item)} · {['负责人', '管理员', '成员', '访客'][item.currentRole]} · {['正常', '已归档', '关闭处理中', '已删除'][item.state]}</Typography.Text>
          </Flex>
        }))}>
        {membership?.status}
        <MembershipInbox workspace/>
        {(task?.management || task?.chat) && <Flex gap={8} wrap>
          {task.management &&
            <Button onClick={() => navigation.request(() => history.push(aiLocation('models', userId, {
              scope: task.management, model: task.model, execution: task.model ? task.scope : undefined,
              chat: task.chat, returnTo: task.chat ? 'chat' : undefined,
            })))}>{task.model ? '返回模型详情' : '返回模型管理'}</Button>}
          {task.chat && <Button disabled={chatReturn.changed} loading={chatReturn.loading}
                                onClick={chatReturn.back}>返回聊天</Button>}
        </Flex>}
        {chatReturn.changed && task?.chat && <Alert type="info" title="原聊天空间已变化，请重新确认当前使用空间。"/>}
        {chatReturn.error && <Alert type="warning" title={chatReturn.error}/>}
        {locationError && <Alert type="warning" title={locationError}/>}
        {locating && <Typography.Paragraph role="status">正在核验工作空间…</Typography.Paragraph>}
        {creating ? <NewWorkspaceForm userId={userId} initialTenantId={initialTenantId}
                                      onUnsafeChange={setFormUnsafe}
                                      onCancel={() => navigation.request(() => setCreating(false))}
                                      onSaved={view => {
                                        setCreating(false);
                                        setTargetTab('overview');
                                        setTarget(view);
                                      }}/>
          : target ? <>
            <WorkspaceEntry key={keyOf(target.scope)} userId={userId} target={target}/>
            <WorkspaceDetailForm userId={userId} scope={target.scope} tab={targetTab} focusedModel={focusedModel}
                                 focusBudget={task?.focus === 'budget'}
                                 onTabChange={setTargetTab} onUnsafeChange={setFormUnsafe} onSaved={setTarget}
                                 onAccessLost={() => {
                                   setTarget(null);
                                   setLocationError('当前账号已无法访问此工作空间。');
                                 }}/>
          </> : !locating && <Typography.Paragraph>
            从左侧选择工作空间，或点击列表上方的新建按钮。
          </Typography.Paragraph>}
      </MasterDetailLayout>
    </div>
    <Modal title={preferencePending ? '偏好保存结果待确认' : '偏好保存失败'}
           open={preferenceDialogOpen && !preferenceSaving}
           okText={canRetryPreference ? '重试原操作' : '知道了'}
           cancelText="稍后处理"
           footer={canRetryPreference ? undefined : (_, {OkBtn}) => <OkBtn/>}
           onCancel={() => setPreferenceDialogOpen(false)}
           onOk={() => {
             setPreferenceDialogOpen(false);
             if (canRetryPreference) void directory.retry();
           }}>
      {canRetryPreference
        ? '暂时无法确认偏好是否已保存。请重试原操作，或稍后点击偏好图标继续确认。'
        : preferenceFailure?.message}
    </Modal>
  </ViewportPage>;
}

function NewWorkspaceForm({userId, initialTenantId, onSaved, onCancel, onUnsafeChange}: {
  userId: string; initialTenantId: string; onSaved: (view: WorkspaceView) => void;
  onCancel: () => void; onUnsafeChange: (unsafe: boolean) => void;
}) {
  const fetchPage = useCallback(async (cursor: string | null, signal: AbortSignal) =>
    (await browseAccessibleTenants({cursor}, {signal})).body.data, []);
  const tenants = useConfigurationDirectory(fetchPage, tenantKey);
  return <>
    {tenants.error && <Alert type="warning" title={tenants.error}
                             action={<Button onClick={tenants.reload}>重新读取租户</Button>}/>}
    <WorkspaceDetailForm userId={userId} initialTenantId={initialTenantId}
                         tenants={tenants.records.map(tenant => ({value: tenant.id, label: tenant.name}))}
                         onMoreTenants={tenants.nextCursor && !tenants.expired ? tenants.more : undefined}
                         onSaved={onSaved} onCancel={onCancel} onUnsafeChange={onUnsafeChange}/>
  </>;
}

function WorkspacePreferenceActions({workspace: {scope, name}, directory, disabled, onChange}: {
  workspace: WorkspaceView; directory: ReturnType<typeof useWorkspaceManagement>; disabled: boolean;
  onChange: (change: PreferenceChange) => void;
}) {
  const preference = directory.preference;
  const startup = sameScope(preference?.startupScope, scope);
  const favorite = directory.favorite(scope);
  return <>
    <Tooltip title={favorite ? '取消收藏' : '收藏此空间'}>
      <Button size="small" variant="text" color={favorite ? 'gold' : 'default'}
              aria-label={`收藏 ${name}`} aria-pressed={favorite}
              aria-disabled={directory.preferenceSaving || undefined}
              icon={<DynamicIcon iconName={favorite ? 'StarFilled' : 'StarOutlined'}/>}
              disabled={disabled || !preference}
              onClick={() => {
                if (!preference) return;
                const favorites = favorite
                  ? preference.favorites.filter(item => !sameScope(item, scope))
                  : [...preference.favorites, scope];
                onChange({favorites});
              }}/>
    </Tooltip>
    <Tooltip title={startup ? '取消启动空间设置' : '设为启动空间'}>
      <Button size="small" variant="text" color={startup ? 'primary' : 'default'}
              aria-label={`启动空间 ${name}`} aria-pressed={startup}
              aria-disabled={directory.preferenceSaving || undefined}
              icon={<DynamicIcon iconName={startup ? 'HomeFilled' : 'HomeOutlined'}/>}
              disabled={disabled || !preference}
              onClick={() => onChange({startupScope: startup ? null : scope})}/>
    </Tooltip>
  </>;
}

function WorkspaceEntry({userId, target}: { userId: string; target: WorkspaceView }) {
  const navigation = useConfigurationNavigation();
  const selection = useRef<ReturnType<typeof retainWorkspaceSelection> | null>(null);
  const live = useRef(true);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<'success' | 'failure'>();
  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      selection.current?.release();
      selection.current = null;
    };
  }, []);

  function enter() {
    navigation.request(() => {
      if (!live.current) return;
      selection.current ??= retainWorkspaceSelection(userId);
      setLoading(true);
      setResult(undefined);
      void selection.current.select(target.scope, () => {
        if (live.current) setResult('success');
      }).then(entered => {
        if (!entered && live.current) setResult(value => value ?? 'failure');
      }).finally(() => {
        if (live.current) setLoading(false);
      });
    });
  }

  return <Flex vertical gap={8}>
    <Flex justify="space-between" align="center" wrap gap={8}>
      <Typography.Text strong>{target.name}</Typography.Text>
      <Button type="primary" loading={loading} onClick={enter}>进入此空间</Button>
    </Flex>
    {result && <Alert type={result === 'success' ? 'success' : 'warning'}
                      title={result === 'success' ? '已进入此空间，聊天使用空间已切换。'
                        : '未切换使用空间，请检查访问资格或处理待确认操作后重试。'}/>}
  </Flex>;
}
