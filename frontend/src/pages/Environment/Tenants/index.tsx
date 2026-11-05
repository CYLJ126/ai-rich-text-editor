import React, {useCallback, useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Descriptions, Flex, Input, Select, Tabs, Typography} from 'antd';
import MasterDetailLayout from '@/components/MasterDetailLayout';
import ViewportPage from '@/components/MasterDetailLayout/ViewportPage';
import {useAiRoute} from '@/features/ainew/aiNavigation';
import ConfigurationBoundary, {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';
import ConfigurationTarget from '@/features/configuration/ConfigurationTarget';
import {readConfigurationLocation, type TenantTab} from '@/features/configuration/configurationLocation';
import {notifyAiConfiguration} from '@/features/workspace/aiConfigurationEvents';
import PolicyEditor from '@/features/workspace/PolicyEditor';
import {
  browseAccessibleTenants,
  getTenant,
  type TenantDetail,
  type TenantSummary,
  type TenantType
} from '@/services/platform/tenant';
import TenantWorkspaces from './TenantWorkspaces';
import {configurationReadError, useConfigurationDirectory} from '@/features/configuration/useConfigurationDirectory';
import './index.css';

const tenantKey = (tenant: TenantSummary) => tenant.id;
const roles = ['负责人', '管理员', '成员'];
const types = ['个人托管', '组织'];
const tabs = new Set(['overview', 'workspaces', 'policy']);

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
    <TenantConfiguration userId={userId} initialTenantId={initialTenantId} initialTab={initialTab}/>
  </ConfigurationBoundary>;
}

function TenantConfiguration({userId, initialTenantId, initialTab}: {
  userId: string; initialTenantId?: string; initialTab: TenantTab;
}) {
  const [target, setTarget] = useState(() => initialTenantId ? {id: initialTenantId, tab: initialTab} : null);
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

  useEffect(() => () => personalReader.current?.abort(), []);

  function cancelPersonal() {
    personalReader.current?.abort();
    setPersonalLoading(false);
    setPersonalNotice('');
  }

  function choose(id: string) {
    if (id === target?.id) return;
    navigation.request(() => {
      cancelPersonal();
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
      <MasterDetailLayout label="租户管理" actions={[]} selectedKey={target?.id} onSelect={choose}
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
                          </Flex>}
                          listFooter={<Flex vertical gap={8} className="tenant-directory-controls">
                            {directory.error && <Alert type="warning" title={directory.error}/>}
                            {directory.nextCursor && !directory.expired &&
                              <Button aria-label="继续加载租户" loading={directory.loading}
                                      onClick={directory.more}>继续加载租户</Button>}
                            <Button aria-label="刷新租户目录" loading={directory.loading}
                                    onClick={directory.reload}>刷新租户目录</Button>
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
        {target ? <TenantDetails key={target.id} userId={userId} tenantId={target.id} initialTab={target.tab}/>
          : <Typography.Paragraph>从左侧选择租户，查看概览、你可访问的工作空间及租户政策。</Typography.Paragraph>}
        <Button
          onClick={() => navigation.request(() => history.push('/Environment/Workspaces'))}>前往工作空间配置</Button>
      </MasterDetailLayout>
    </div>
  </ViewportPage>;
}

function TenantDetails({userId, tenantId, initialTab}: { userId: string; tenantId: string; initialTab: TenantTab }) {
  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [error, setError] = useState('');
  const [tab, setTab] = useState(initialTab);
  const [revision, setRevision] = useState(0);
  const navigation = useConfigurationNavigation();

  useEffect(() => {
    const request = new AbortController();
    setError('');
    setDetail(null);
    void getTenant(tenantId, {signal: request.signal}).then(({body}) => {
      if (!request.signal.aborted) setDetail(body.data);
    }).catch(failure => {
      if (!request.signal.aborted) setError(configurationReadError(failure, '租户详情读取失败，请重试。'));
    });
    return () => request.abort();
  }, [tenantId, revision]);

  if (error) return <Alert type="warning" title={error}
                           action={<Button onClick={() => setRevision(value => value + 1)}>重新读取租户</Button>}/>;
  if (!detail) return <Typography.Paragraph role="status">正在读取租户详情…</Typography.Paragraph>;
  const policyAllowed = detail.actions.includes('tenant:policy:write');
  return <>
    <Flex align="start" justify="space-between" wrap gap={8}>
      <ConfigurationTarget name={detail.name} scope={{tenantId}} purpose="租户配置"/>
      <Button onClick={() => navigation.request(() => setRevision(value => value + 1))}>刷新租户详情</Button>
    </Flex>
    <Tabs activeKey={tab} onChange={value => setTab(value as TenantTab)} destroyOnHidden={false} items={[
      {
        key: 'overview', label: '概览', children: <Descriptions size="small" column={{xs: 1, sm: 1, md: 2}} items={[
          {key: 'name', label: '租户名称', children: detail.name},
          {key: 'type', label: '租户类型', children: types[detail.type]},
          {key: 'role', label: '我的角色', children: roles[detail.currentRole]},
          {key: 'state', label: '状态', children: '正常'},
        ]}/>
      },
      {key: 'workspaces', label: '工作空间', children: <TenantWorkspaces userId={userId} tenantId={tenantId}/>},
      {
        key: 'policy', label: '租户政策', children: policyAllowed
          ? <PolicyEditor userId={userId} scope={{tenantId}} kind="tenant"
                          onSaved={() => notifyAiConfiguration(userId, {tenantId}, true)}/>
          : <Alert type="info" title="租户政策由负责人和管理员维护。"/>
      },
    ]}/>
  </>;
}
