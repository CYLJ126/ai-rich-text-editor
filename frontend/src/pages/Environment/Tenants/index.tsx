import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Card, Descriptions, Flex, Input, Select, Tabs, Typography} from 'antd';
import MasterDetailLayout from '@/components/MasterDetailLayout';
import ViewportPage from '@/components/MasterDetailLayout/ViewportPage';
import {useAiRoute} from '@/features/ainew/aiNavigation';
import {AiApiError} from '@/services/arte-ai/request';
import ConfigurationBoundary, {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import {readConfigurationLocation, type TenantTab} from '@/features/configuration/configurationLocation';
import {notifyAiConfiguration} from '@/features/workspace/aiConfigurationEvents';
import {MembershipSession, useMembershipSession} from '@/features/workspace/MembershipSession';
import {
  MembershipInbox,
  MembershipPanel,
  useMembershipCapabilities,
  useMembershipTarget
} from '@/features/workspace/MembershipPanel';
import PolicyEditor from '@/features/workspace/PolicyEditor';
import {
  browseAccessibleTenants,
  getTenant,
  getTenantCreationOptions,
  type TenantCreationOptions,
  type TenantDetail,
  type TenantSummary,
  type TenantType
} from '@/services/platform/tenant';
import TenantWorkspaces from './TenantWorkspaces';
import TenantNameEditor from './TenantNameEditor';
import {type TenantGovernance, useTenantGovernance} from './useTenantGovernance';
import {configurationReadError, useConfigurationDirectory} from '@/features/configuration/useConfigurationDirectory';
import './index.css';

const tenantKey = (tenant: TenantSummary) => tenant.id;
const roles = ['负责人', '管理员', '成员'];
const types = ['个人托管', '组织'];
const tabs = new Set(['overview', 'workspaces', 'policy', 'members']);

export default function TenantConfigurationPage() {
  const {initialState} = useModel('@@initialState');
  const userId = String(initialState?.currentUser?.id ?? initialState?.currentUser?.userid ?? '');
  const search = useAiRoute();
  if (!userId) return <Alert type="info" title="请登录后管理租户。"/>;
  try {
    const {tenantId, workspaceId, tab} = readConfigurationLocation(search, userId);
    if (workspaceId || (tab !== undefined && !tabs.has(tab))) throw new Error('租户定位无效，请重新选择。');
    return <TenantSettings key={JSON.stringify([userId, tenantId, tab])} userId={userId}
                           initialTenantId={tenantId} initialTab={tab as TenantTab | undefined}/>;
  } catch (failure) {
    return <Alert type="warning" title={failure instanceof Error ? failure.message : '租户定位无效，请重新选择。'}
                  action={<Button onClick={() => history.replace('/Environment/Tenants')}>打开租户目录</Button>}/>;
  }
}

export function TenantSettings({userId, initialTenantId, initialTab = 'overview'}: {
  userId: string; initialTenantId?: string; initialTab?: TenantTab;
}) {
  const [closed, setClosed] = useState(false);
  useEffect(() => {
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId) setClosed(true);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => window.removeEventListener('arte-account-session-cleared', logout);
  }, [userId]);
  if (closed) return <Alert type="warning" title="账号会话已结束，请重新登录后打开租户配置。"/>;
  return <ConfigurationBoundary userId={userId}>
    <MembershipSession userId={userId}>
      <TenantConfiguration userId={userId} initialTenantId={initialTenantId} initialTab={initialTab}/>
    </MembershipSession>
  </ConfigurationBoundary>;
}

function TenantConfiguration({userId, initialTenantId, initialTab}: {
  userId: string; initialTenantId?: string; initialTab: TenantTab;
}) {
  const governance = useTenantGovernance(userId, ({command, receipt}) => {
    setCreating(false);
    setTarget(current => command.kind === 'update' && current?.id === receipt.value.id
      ? current : {id: receipt.value.id, tab: 'overview'});
    directory.reload();
    reloadOptions(value => value + 1);
  });
  const original = governance.pending?.command;
  const [target, setTarget] = useState(() => original?.kind === 'update'
    ? {id: original.request.tenantId, tab: 'overview' as const}
    : initialTenantId ? {id: initialTenantId, tab: initialTab} : null);
  const [creating, setCreating] = useState(original?.kind === 'create');
  const [options, setOptions] = useState<TenantCreationOptions>();
  const [optionsError, setOptionsError] = useState(false);
  const [optionsRevision, reloadOptions] = useState(0);
  const [input, setInput] = useState('');
  const [search, setSearch] = useState('');
  const [type, setType] = useState<TenantType | 'all'>('all');
  const [personalLoading, setPersonalLoading] = useState(false);
  const [personalNotice, setPersonalNotice] = useState('');
  const personalReader = useRef<AbortController | null>(null);
  const navigation = useConfigurationNavigation();
  const fetchPage = useCallback(async (cursor: string | null, signal: AbortSignal) =>
    (await browseAccessibleTenants({
      cursor,
      search,
      type: type === 'all' ? undefined : type
    }, {signal})).body.data, [search, type]);
  const directory = useConfigurationDirectory(fetchPage, tenantKey);
  const membership = useMembershipSession();
  const membershipRevision = membership?.revision ?? 0;
  const reloadDirectory = useRef(directory.reload);
  reloadDirectory.current = directory.reload;
  useEffect(() => {
    if (membershipRevision) reloadDirectory.current();
  }, [membershipRevision]);

  useEffect(() => () => personalReader.current?.abort(), []);
  useEffect(() => {
    const request = new AbortController();
    setOptions(undefined);
    setOptionsError(false);
    void getTenantCreationOptions({signal: request.signal}).then(({body}) => {
      if (!request.signal.aborted) setOptions(body.data);
    }).catch(() => {
      if (!request.signal.aborted) setOptionsError(true);
    });
    return () => request.abort();
  }, [optionsRevision]);

  const blocked = governance.blocked || governance.saving || !!governance.pending;

  function cancelPersonal() {
    personalReader.current?.abort();
    setPersonalLoading(false);
    setPersonalNotice('');
  }

  function choose(id: string) {
    if (id === target?.id && !creating) return;
    navigation.request(() => {
      cancelPersonal();
      setCreating(false);
      setTarget({id, tab: initialTenantId ? 'overview' : initialTab});
    });
  }

  function findPersonal() {
    navigation.request(() => {
      personalReader.current?.abort();
      const request = new AbortController();
      personalReader.current = request;
      setPersonalLoading(true);
      setPersonalNotice('');
      void (async () => {
        try {
          let cursor: string | null = null;
          const visited = new Set<string>();
          do {
            const {body: {data}} = await browseAccessibleTenants({cursor, type: 0}, {signal: request.signal});
            if (request.signal.aborted) return;
            const personal = data.records.find(tenant => tenant.isPersonal);
            if (personal) {
              navigation.request(() => {
                if (request.signal.aborted) return;
                setCreating(false);
                if (target?.id !== personal.id) setTarget({id: personal.id, tab: 'overview'});
              });
              return;
            }
            cursor = data.nextCursor;
            if (cursor && visited.has(cursor)) throw new Error('游标无效');
            if (cursor) visited.add(cursor);
          } while (cursor);
          setPersonalNotice('当前账号没有可访问的个人租户。');
        } catch (failure) {
          if (!request.signal.aborted) setPersonalNotice(configurationReadError(failure, '个人租户定位失败，请重试。'));
        } finally {
          if (!request.signal.aborted) setPersonalLoading(false);
        }
      })();
    });
  }

  return <ViewportPage className="tenant-configuration-page" label="租户配置">
    <div className="tenant-configuration-content">
      <MasterDetailLayout label="租户管理" selectedKey={creating ? undefined : target?.id} onSelect={choose}
                          actions={options?.organizationAllowed ? [{
                            key: 'create', icon: 'PlusOutlined', label: '新建组织租户', disabled: blocked,
                            onClick: () => navigation.request(() => {
                              cancelPersonal();
                              setCreating(true);
                            })
                          }] : []}
                          emptyText={directory.loading ? '正在读取租户目录…' : directory.error ? '租户目录暂不可用。'
                            : directory.exhausted ? '没有匹配的租户。' : '本页暂无匹配租户，可以继续加载。'}
                          listHeader={<Flex vertical gap={8} className="tenant-directory-controls">
                            <Input.Search aria-label="搜索租户" placeholder="搜索租户名称" allowClear maxLength={100}
                                          value={input} enterButton="搜索"
                                          onChange={event => setInput(event.target.value)}
                                          onSearch={value => {
                                            if (value.trim() === search) directory.reload();
                                            else setSearch(value.trim());
                                          }}/>
                            <Select aria-label="筛选租户类型" value={type} onChange={setType}
                                    options={[{value: 'all', label: '全部类型'}, {
                                      value: 0,
                                      label: '个人托管'
                                    }, {value: 1, label: '组织'}]}/>
                            <Button aria-label="定位我的个人租户" loading={personalLoading}
                                    onClick={findPersonal}>定位我的个人租户</Button>
                            {personalNotice && <Alert type="info" title={personalNotice}/>}
                            {optionsError && <Typography.Text type="secondary">组织创建资格暂未核验。</Typography.Text>}
                          </Flex>}
                          listFooter={<Flex vertical gap={8} className="tenant-directory-controls">
                            {directory.error && <Alert type="warning" title={directory.error}/>}
                            {directory.nextCursor && !directory.expired &&
                              <Button aria-label="继续加载租户" loading={directory.loading}
                                      onClick={directory.more}>继续加载租户</Button>}
                            <Button aria-label="刷新租户目录" loading={directory.loading}
                                    onClick={() => {
                                      directory.reload();
                                      reloadOptions(value => value + 1);
                                    }}>刷新租户目录</Button>
                          </Flex>}
                          items={directory.records.map(tenant => ({
                            key: tenant.id,
                            label: `选择租户 ${tenant.name}`,
                            content: <Flex vertical gap={4}>
                              <Typography.Text strong>{tenant.name}</Typography.Text>
                              <Typography.Text type="secondary">
                                {types[tenant.type]} · {roles[tenant.currentRole]}{tenant.isPersonal ? ' · 我的个人租户' : ''}
                              </Typography.Text>
                            </Flex>,
                          }))}>
        {governance.error && <Alert type="warning" title={governance.error}/>}
        {governance.blocked && <Alert type="warning" title="租户操作记录无法可靠读取或保存，请检查浏览器存储。"/>}
        {governance.pending && <Card size="small" title="待确认租户操作">
          <Flex vertical gap={8} align="start">
            <Typography.Text>{governance.pending.command.kind === 'create' ? '新建组织' : `修改租户 ${governance.pending.command.request.tenantId}`}
              {' · '}{governance.pending.command.request.name}</Typography.Text>
            <Button aria-label="确认原租户操作" loading={governance.saving} disabled={governance.blocked}
                    onClick={() => void governance.run()}>确认原租户操作</Button>
          </Flex>
        </Card>}
        {membership?.status}
        <MembershipInbox workspace={false}/>
        {governance.confirmed && <Card size="small" title="租户操作已确认">
          <Flex vertical gap={8} align="start">
            <Typography.Text>{governance.confirmed.receipt.value.name} ·
              租户版本 {governance.confirmed.receipt.resultVersion}</Typography.Text>
            <Button loading={governance.checking} disabled={governance.saving}
                    onClick={() => void governance.query()}>查询操作结果</Button>
            {governance.verification && <Typography.Text role="status">{governance.verification}</Typography.Text>}
          </Flex>
        </Card>}
        {creating ? <Card title="新建组织租户" size="small">
          <Typography.Paragraph>创建后你是该组织的首位负责人，可在组织概览中继续管理。</Typography.Paragraph>
          <TenantNameEditor create initialName={original?.kind === 'create' ? original.request.name : ''}
                            disabled={blocked} submitDisabled={!options?.organizationAllowed}
                            onSubmit={name => governance.run({kind: 'create', request: {name}})}
                            onCancel={() => setCreating(false)}/>
        </Card> : target ? <TenantDetails key={target.id} userId={userId} tenantId={target.id} initialTab={target.tab}
                                          governance={governance} blocked={blocked}/>
          : <Typography.Paragraph>从左侧选择租户，查看概览、你可访问的工作空间及租户政策。</Typography.Paragraph>}
        <Button
          onClick={() => navigation.request(() => history.push('/Environment/Workspaces'))}>前往工作空间配置</Button>
      </MasterDetailLayout>
    </div>
  </ViewportPage>;
}

function TenantDetails({userId, tenantId, initialTab, governance, blocked}: {
  userId: string; tenantId: string; initialTab: TenantTab; governance: TenantGovernance; blocked: boolean;
}) {
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [error, setError] = useState('');
  const [tab, setTab] = useState(initialTab);
  const [revision, setRevision] = useState(0);
  const [policyRevision, setPolicyRevision] = useState(0);
  const original = governance.pending?.command;
  const restoring = original?.kind === 'update' && original.request.tenantId === tenantId;
  const [editing, setEditing] = useState(restoring);
  const [nameVersion, setNameVersion] = useState(restoring ? original.request.expectedTenantVersion : undefined);
  const [rebaseError, setRebaseError] = useState('');
  const [rebasing, setRebasing] = useState(false);
  const rebaseReader = useRef<AbortController | null>(null);
  const membership = useMembershipSession();
  const membershipTarget = useMembershipTarget(detail?.type === 1 ? tenantId : undefined);
  const caps = useMembershipCapabilities(membershipTarget, revision);
  const confirmed = governance.confirmed?.receipt.value.id === tenantId ? governance.confirmed : undefined;
  const navigation = useConfigurationNavigation();

  useEffect(() => {
    const request = new AbortController();
    setError('');
    void getTenant(tenantId, {signal: request.signal}).then(({body}) => {
      if (!request.signal.aborted) setDetail(body.data);
    }).catch(failure => {
      if (!request.signal.aborted) {
        if (failure instanceof AiApiError && [401, 403, 404].includes(failure.httpStatus)) setDetail(null);
        setError(configurationReadError(failure, '租户详情读取失败，请重试。'));
      }
    });
    return () => request.abort();
  }, [tenantId, revision, confirmed, membership?.revision]);
  useEffect(() => () => rebaseReader.current?.abort(), []);
  useEffect(() => {
    if (confirmed) {
      setEditing(false);
      if (confirmed.command.kind === 'update') setPolicyRevision(value => value + 1);
    }
  }, [confirmed]);

  async function rebase() {
    rebaseReader.current?.abort();
    const request = new AbortController();
    rebaseReader.current = request;
    setRebasing(true);
    setRebaseError('');
    try {
      const {body} = await getTenant(tenantId, {signal: request.signal});
      if (!request.signal.aborted) {
        setDetail(body.data);
        setNameVersion(body.data.version);
        governance.clearConflict();
      }
    } catch (failure) {
      if (!request.signal.aborted) setRebaseError(configurationReadError(failure, '最新租户版本读取失败。'));
    } finally {
      if (!request.signal.aborted) setRebasing(false);
    }
  }

  if (error && !detail) return <Alert type="warning" title={error}
                           action={<Button onClick={() => setRevision(value => value + 1)}>重新读取租户</Button>}/>;
  if (!detail) return <Typography.Paragraph role="status">正在读取租户详情…</Typography.Paragraph>;
  const policyAllowed = detail.actions.includes('tenant:policy:write');
  return <>
    {error && <Alert type="warning" title={error}/>}
    <Flex align="start" justify="space-between" wrap gap={8}>
      <ConfigurationTarget name={detail.name} scope={{tenantId}} purpose="租户配置"/>
      <Button onClick={() => navigation.request(() => setRevision(value => value + 1))}>刷新租户详情</Button>
    </Flex>
    <Tabs activeKey={tab === 'members' && !caps?.manage ? 'overview' : tab}
          onChange={value => setTab(value as TenantTab)} destroyOnHidden={false} items={[
      {
        key: 'overview', label: '概览', children: <Flex vertical gap={12} align="start">
          <Descriptions size="small" column={{xs: 1, sm: 1, md: 2}} items={[
          {key: 'name', label: '租户名称', children: detail.name},
          {key: 'type', label: '租户类型', children: types[detail.type]},
          {key: 'role', label: '我的角色', children: roles[detail.currentRole]},
          {key: 'state', label: '状态', children: '正常'},
        ]}/>
          {confirmed?.command.kind === 'create' && <Typography.Paragraph>
            组织租户已建立。可在工作空间页签查看或创建空间。
          </Typography.Paragraph>}
          {detail.type === 0 &&
            <Typography.Text type="secondary">个人租户的托管关系及默认工作空间由系统维护。</Typography.Text>}
          {detail.actions.includes('tenant:update') && (editing ?
            <TenantNameEditor initialName={restoring ? original.request.name : detail.name}
                              disabled={blocked || rebasing || !!error}
                              submitDisabled={governance.conflict === tenantId}
                              onSubmit={name => governance.run({
                                kind: 'update', request: {
                                  tenantId, expectedTenantVersion: nameVersion ?? detail.version, name
                                }
                              })}
                              onCancel={() => {
                                setEditing(false);
                                setRebaseError('');
                                governance.clearConflict();
                              }}/> :
            <Button disabled={blocked || !!error} onClick={() => {
              setNameVersion(detail.version);
              setEditing(true);
            }}>编辑租户名称</Button>)}
          {editing && governance.conflict === tenantId && <Button loading={rebasing} disabled={blocked}
                                                                  onClick={() => void rebase()}>读取最新版本并保留名称</Button>}
          {rebaseError && <Alert type="warning" title={rebaseError}/>}
        </Flex>
      },
      ...(caps?.manage && membershipTarget ? [{
        key: 'members', label: '成员',
        children: <MembershipPanel target={membershipTarget} version={detail.version} caps={caps} disabled={blocked}/>
      }] : []),
      {key: 'workspaces', label: '工作空间', children: <TenantWorkspaces userId={userId} tenantId={tenantId}/>},
      {
        key: 'policy', label: '租户政策', children: policyAllowed
          ? <PolicyEditor userId={userId} scope={{tenantId}} kind="tenant" refreshRevision={policyRevision}
                          onSaved={() => notifyAiConfiguration(userId, {tenantId}, true)}/>
          : <Alert type="info" title="租户政策由负责人和管理员维护。"/>
      },
    ]}/>
  </>;
}
