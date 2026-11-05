import React, {useEffect, useRef, useState} from 'react';
import {useModel} from '@umijs/max';
import {Alert, Button, Flex, Input, Tooltip, Typography} from 'antd';
import MasterDetailLayout from '@/components/MasterDetailLayout';
import ViewportPage from '@/components/MasterDetailLayout/ViewportPage';
import DynamicIcon from '@/components/DynamicIcon/MyDynamicIcon';
import WorkspaceDetailForm from '@/features/workspace/WorkspaceDetailForm';
import WorkspacePolicyDialog from '@/features/workspace/WorkspacePolicyDialog';
import {useWorkspaceManagement} from '@/features/workspace/useWorkspace';
import {usePolicyDirectory} from '@/features/workspace/usePolicyDirectory';
import {checkWorkspaceAccess, type WorkspaceScope, type WorkspaceView} from '@/services/platform/workspace';
import ConfigurationBoundary, {
  useConfigurationFallback,
  useConfigurationNavigation
} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import {sameScope} from '@/features/ainew/storage';
import '@/features/ainew/personalModels.css';
import './index.css';

const keyOf = (scope: WorkspaceScope) => JSON.stringify([scope.tenantId, scope.workspaceId]);

export default function WorkspaceConfigurationPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  if (!userId) return <Alert type="info" title="请登录后管理工作空间。"/>;
  return <ConfigurationBoundary key={userId} userId={userId}><WorkspaceConfiguration
    userId={userId}/></ConfigurationBoundary>;
}

/** 配置目标只存在本页状态中；URL、聊天当前空间和刷新均不会自动选择卡片。 */
function WorkspaceConfiguration({userId}: { userId: string }) {
  const directory = useWorkspaceManagement(userId);
  const candidates = usePolicyDirectory(userId);
  const [target, setTarget] = useState<WorkspaceView | null>(null);
  const [creating, setCreating] = useState(false);
  const [initialTenantId, setInitialTenantId] = useState('');
  const [filter, setFilter] = useState(directory.search);
  const [formUnsafe, setFormUnsafe] = useState(false);
  const [policyUnsafe, setPolicyUnsafe] = useState(false);
  const [policyOpen, setPolicyOpen] = useState(false);
  const [tenants, setTenants] = useState<{ value: string; label: string }[]>([]);
  const alive = useRef(true);
  const tenantIds = [...new Set(candidates.records.map(item => item.scope.tenantId))].sort().join('\n');
  const unsafe = formUnsafe || policyUnsafe || directory.saving || !!directory.pending || directory.recoveryBlocked || directory.unsafeToLeave;
  const navigation = useConfigurationNavigation();
  useConfigurationFallback(formUnsafe, 'workspace', '空间详情');
  useConfigurationFallback(directory.saving || !!directory.pending || directory.recoveryBlocked || directory.unsafeToLeave,
    'workspace-operation', '空间管理操作');

  useEffect(() => {
    setFilter(directory.search);
  }, [directory.search]);

  useEffect(() => {
    alive.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      alive.current = false;
      setTarget(null);
      setCreating(false);
      setPolicyOpen(false);
      setTenants([]);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      alive.current = false;
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId]);

  useEffect(() => {
    const controller = new AbortController();
    setTenants([]);
    void Promise.all(tenantIds.split('\n').filter(Boolean).map(async tenantId => {
      const permission = await checkWorkspaceAccess({tenantId}, 'tenant', 'tenant:workspace:create', {signal: controller.signal}).catch(() => null);
      return permission?.body.data.allowed ? {value: tenantId, label: `租户 · ${tenantId}`} : null;
    })).then(values => {
      if (!controller.signal.aborted && alive.current) setTenants(values.filter(value => value !== null));
    });
    return () => controller.abort();
  }, [tenantIds, userId]);

  function recover() {
    const operation = directory.pending;
    if (operation?.kind === 'rename') {
      // 显式点击恢复入口后才打开原目标；完整详情仍由表单重新读取验证。
      setCreating(false);
      setTarget({
        scope: operation.request.scope, name: operation.request.name,
        description: operation.request.description, version: operation.request.expectedVersion, state: 0, currentRole: 3
      });
    } else if (operation?.kind === 'create') {
      setTarget(null);
      setInitialTenantId(operation.request.tenantId);
      setCreating(true);
    } else void directory.retry();
  }

  return <ViewportPage className="personal-models-page workspace-configuration-page" label="工作空间配置">
    <ConfigurationTarget name={creating ? '新建工作空间' : target?.name} scope={target?.scope} purpose="工作空间配置"/>
    <div className="workspace-configuration-content">
      <MasterDetailLayout
        label="工作空间管理" actions={[]}
        selectedKey={!creating && target ? keyOf(target.scope) : undefined}
        onSelect={key => navigation.request(() => {
          const item = directory.records.find(value => keyOf(value.scope) === key);
          if (item) {
            setPolicyOpen(false);
            setCreating(false);
            setTarget(item);
          }
        })}
        listHeader={<Flex gap={8} align="center" className="workspace-directory-actions">
          <Input.Search aria-label="搜索工作空间" placeholder="搜索工作空间" value={filter}
                        maxLength={100} allowClear
                        enterButton={<Button aria-label="搜索" title="搜索工作空间"
                                             icon={<DynamicIcon iconName="SearchOutlined"/>}/>}
                        loading={directory.loading} disabled={directory.saving}
                        onChange={event => setFilter(event.target.value)}
                        onSearch={value => void directory.load(value)}/>
          <Tooltip title="租户与空间政策">
            <Button aria-label="租户与空间政策"
                    icon={<DynamicIcon iconName="ControlOutlined"/>}
                    disabled={!target || creating || policyOpen}
                    onClick={() => navigation.request(() => setPolicyOpen(true))}/>
          </Tooltip>
          <Tooltip title="清除启动空间偏好">
            <Button aria-label="清除启动空间偏好"
                    icon={<DynamicIcon iconName="ClearOutlined"/>}
                    disabled={unsafe || !directory.preference?.startupScope || policyOpen}
                    onClick={() => void directory.updatePreference({startupScope: null})}/>
          </Tooltip>
          <Tooltip title="新建工作空间">
            <Button aria-label="新建工作空间"
                    icon={<DynamicIcon iconName="PlusOutlined"/>}
                    disabled={!tenants.length || policyOpen || directory.saving}
                    onClick={() => navigation.request(() => {
                      setInitialTenantId(tenants.some(item => item.value === target?.scope.tenantId) ? target!.scope.tenantId : '');
                      setTarget(null);
                      setCreating(true);
                    })}/>
          </Tooltip>
        </Flex>}
        listFooter={<Flex vertical gap={8} style={{marginTop: 12}}>
          {directory.warning && <Alert type="warning" title={directory.warning}/>}
          {directory.error != null && <Alert type="warning"
                                             title={directory.error instanceof Error ? directory.error.message : '空间目录读取失败。'}/>}
          {candidates.error && <Alert type="warning" title={candidates.error}
                                      action={<Button
                                        onClick={() => void candidates.reload()}>重新读取租户</Button>}/>}
          {directory.pending && <Alert type="warning" title="有待确认空间操作，请处理原操作。"
                                       action={<Button
                                         disabled={directory.saving || directory.recoveryBlocked}
                                         onClick={recover}>处理原空间操作</Button>}/>}
          {!directory.exhausted && directory.nextCursor && !directory.cursorExpired &&
            <Button loading={directory.loading} disabled={directory.loading || directory.saving}
                    onClick={() => void directory.loadMore()}>继续加载</Button>}
          {directory.cursorExpired &&
            <Button onClick={() => void directory.load(filter)}>重新加载目录</Button>}
          {candidates.hasMore && <Button disabled={candidates.loading}
                                         onClick={() => void candidates.more()}>加载更多可选租户</Button>}
          {directory.loaded && !directory.preference && !directory.error && <Button disabled={unsafe}
                                                                                    onClick={() => void directory.initialize()}>初始化个人默认空间</Button>}
        </Flex>}
        emptyText={directory.loading ? '正在读取空间目录…' : '没有匹配的工作空间。'}
        items={directory.records.map(item => ({
          key: keyOf(item.scope), label: `配置 ${item.name}`, disabled: directory.saving,
          content: <Flex vertical gap={8}>
            <Typography.Text strong>{item.name}</Typography.Text>
            <Typography.Text type="secondary">所属租户：{item.scope.tenantId}</Typography.Text>
            <Typography.Text
              type="secondary">{['负责人', '管理员', '成员', '访客'][item.currentRole]} · {['正常', '已归档', '关闭处理中', '已删除'][item.state]}</Typography.Text>
          </Flex>
        }))}>
        {/* 右侧工作空间表单详情区域：创建表单、编辑/详情表单、未选择时的引导。 */}
        {creating ? <WorkspaceDetailForm
            userId={userId} initialTenantId={initialTenantId} tenants={tenants}
            onMoreTenants={candidates.hasMore ? () => void candidates.more() : undefined}
            onCancel={() => navigation.request(() => {
              setCreating(false);
              setFormUnsafe(false);
            })}
            onUnsafeChange={setFormUnsafe} onSaved={view => {
            setCreating(false);
            setTarget(view);
          }}/>
          : target ? <WorkspaceDetailForm userId={userId} scope={target.scope} onUnsafeChange={setFormUnsafe}
                                          onSaved={setTarget}/> : <Typography.Paragraph>
            1. 点击左侧工作空间以编辑，或查看详情；<br/>
            2. 点击左上方新增图标，以创建新工作空间。
          </Typography.Paragraph>}
        {/* 按钮：收藏、设为启动空间 */}
        {target && !creating && <Flex gap={8} wrap>
          <Button disabled={unsafe || !directory.preference}
                  onClick={() => void directory.updatePreference({
                    favorites: directory.favorite(target.scope)
                      ? directory.preference!.favorites.filter(scope => !sameScope(scope, target.scope))
                      : [...directory.preference!.favorites, target.scope]
                  })}>
            {directory.favorite(target.scope) ? '取消收藏' : '收藏此空间'}
          </Button>
          <Button disabled={unsafe || !directory.preference}
                  onClick={() => void directory.updatePreference({startupScope: target.scope})}>
            {sameScope(directory.preference?.startupScope, target.scope) ? '已设为启动空间' : '设为启动空间'}
          </Button>
        </Flex>}
      </MasterDetailLayout>
    </div>

    {policyOpen && target && <WorkspacePolicyDialog userId={userId} target={target}
                                                    onClose={() => setPolicyOpen(false)}
                                                    onUnsafeChange={setPolicyUnsafe}/>}
  </ViewportPage>;
}
