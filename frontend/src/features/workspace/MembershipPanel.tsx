import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Grid, Input, List, Popconfirm, Select, Table, Typography} from 'antd';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import {AiApiError} from '@/services/arte-ai/request';
import {
  findInvitationTarget,
  type InvitationTarget,
  listInvitations,
  listMembers,
  listMyInvitations,
  type MembershipAction,
  membershipCapabilities,
  type MembershipCapabilities,
  type MembershipInvitation,
  type MembershipMember,
  type MembershipTarget
} from '@/services/platform/membership';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {useMembershipSession} from './MembershipSession';

const roles = ['负责人', '管理员', '成员', '访客'];
const states = ['待接受', '已接受', '已撤销', '已过期', '已拒绝'];
const filterOptions = [{value: -1, label: '全部状态'}, ...states.map((label, value) => ({value, label}))];
const name = (member: MembershipMember) => member.displayName ?? member.subjectId;

export function useMembershipTarget(tenantId?: string, workspaceId?: string): MembershipTarget | undefined {
  return useMemo(() => tenantId ? {tenantId, scope: workspaceId ? {tenantId, workspaceId} : undefined} : undefined,
    [tenantId, workspaceId]);
}

export function useMembershipCapabilities(target?: MembershipTarget, refresh?: number) {
  const session = useMembershipSession();
  const enabled = !!session;
  const revision = session?.revision;
  const [caps, setCaps] = useState<{ target: MembershipTarget; value: MembershipCapabilities }>();
  useEffect(() => {
    if (!target || !enabled) return;
    let request: AbortController | undefined;

    function read() {
      request?.abort();
      const next = new AbortController();
      request = next;
      void membershipCapabilities(target!, {signal: next.signal}).then(value => {
        if (!next.signal.aborted) setCaps({target: target!, value});
      }).catch(() => {
        if (!next.signal.aborted) setCaps(undefined);
      });
    }

    read();
    window.addEventListener('focus', read);
    return () => {
      request?.abort();
      window.removeEventListener('focus', read);
    };
  }, [enabled, target, revision, refresh]);
  return caps?.target === target && caps?.value.available ? caps.value : undefined;
}

type Page<T> = { records: T[]; current: number; size: number; total: number };
type ReadPage<T> = (page: number, signal: AbortSignal) => Promise<Page<T>>;

function usePage<T>(read: ReadPage<T> | undefined, revision: number) {
  const [position, setPosition] = useState<{ read: ReadPage<T> | undefined; current: number }>({read, current: 1});
  const current = position.read === read ? position.current : 1;
  const [data, setData] = useState<Page<T>>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [unavailable, setUnavailable] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!read) return;
    const request = new AbortController();
    setLoading(true);
    setData(undefined);
    setError('');
    setUnavailable(false);
    void read(current, request.signal).then(value => {
      if (!request.signal.aborted) setData(value);
    }).catch(failure => {
      if (request.signal.aborted) return;
      const absent = failure instanceof AiApiError && failure.httpStatus === 404;
      setUnavailable(absent);
      setError(absent ? '当前无法访问成员治理。' : '读取失败，请重新读取。');
    }).finally(() => {
      if (!request.signal.aborted) setLoading(false);
    });
    return () => request.abort();
  }, [read, current, revision, retry]);
  return {
    data, loading, error, unavailable, reload: () => setRetry(value => value + 1),
    pagination: {
      current,
      pageSize: data?.size ?? 20,
      total: data?.total ?? 0,
      showSizeChanger: false,
      onChange: (page: number) => setPosition({read, current: page})
    }
  };
}

export function MembershipPanel({target, targetName, version, caps, disabled}: {
  target: MembershipTarget; targetName?: string; version: number; caps: MembershipCapabilities; disabled?: boolean;
}) {
  const session = useMembershipSession();
  const locked = disabled || !!session?.locked;
  const members = useMemo<ReadPage<MembershipMember> | undefined>(() => caps.manage
    ? (page, signal) => listMembers(target, page, {signal}) : undefined, [target, caps.manage]);
  const [invitationState, setInvitationState] = useState(-1);
  const invites = useMemo<ReadPage<MembershipInvitation> | undefined>(() => caps.manage
    ? (page, signal) => listInvitations(target, page, {signal}, invitationState < 0 ? undefined : invitationState) : undefined, [target, caps.manage, invitationState]);
  const directory = usePage(members, session?.revision ?? 0);
  const invitations = usePage(invites, session?.revision ?? 0);
  const [edit, setEdit] = useState<{ memberId: string; version: number; role: number }>();
  const owner = caps.assignAdmin;
  const space = !!target.scope;
  const scopeName = space ? '工作空间' : '组织租户';
  const run = (action: MembershipAction, payload: Record<string, unknown>) =>
    session?.run({target, ...(targetName ? {targetLabel: targetName} : {}), action, expectedVersion: version, payload});
  useConfigurationDraft({
    group: 'member-role', label: '成员角色草稿', status: edit ? 'dirty' : 'clean', discard: () => {
      setEdit(undefined);
      return true;
    }
  });

  return <Flex vertical gap={12}>
    {caps.invite && <InvitationForm target={target} locked={locked} onIssue={(subjectId, role, days) => {
      const expiry = days === 7 ? {} : {expiresAt: new Date(Date.now() + days * 86400000).toISOString()};
      return run('issue', {...(space ? {targetSubjectId: subjectId, role} : {targetSubjectId: subjectId}), ...expiry});
    }}/>}
    {caps.manage && <>
      <Card title="成员目录" size="small">
        {directory.error && <Alert type="warning" title={directory.error}
                                   action={<Button onClick={directory.reload}>重新读取成员</Button>}/>}
        <Table<MembershipMember> rowKey="memberId" size="small" scroll={{x: 600}}
                                 loading={directory.loading} dataSource={directory.data?.records ?? []}
                                 pagination={directory.pagination} locale={{emptyText: '暂无成员记录'}}
                                 columns={[
                                   {title: '账号', render: (_, member) => name(member)},
                                   {title: '角色', render: (_, member) => roles[member.role]},
                                   {title: '状态', render: (_, member) => member.state === 0 ? '有效' : '已撤销'},
                                   {
                                     title: '操作', render: (_, member) => {
                                       if (member.state !== 0 || member.role === 0 || member.memberId === caps.memberId
                                         || member.role === 1 && !owner) return null;
                                       const options = (space ? [1, 2, 3] : [1, 2])
                                         .filter(value => owner || value !== 1).map(value => ({
                                           value,
                                           label: roles[value]
                                         }));
                                       const selected = edit?.memberId === member.memberId && edit.version === member.version ? edit.role : member.role;
                                       const memberRef = {
                                         memberId: member.memberId,
                                         expectedMemberVersion: member.version
                                       };
                                       return <Flex gap={6} wrap>
                                         {!caps.readOnly && (space || member.role !== 3) && <>
                                           <Select aria-label={`调整 ${name(member)} 的角色`} size="small"
                                                   value={selected}
                                                   disabled={locked} options={options}
                                                   onChange={role => setEdit(role === member.role ? undefined :
                                                     {memberId: member.memberId, version: member.version, role})}/>
                                           <Popconfirm title={`将 ${name(member)} 的角色改为${roles[selected]}？`}
                                                       okText="确认调整"
                                                       onConfirm={async () => {
                                                         if (await run('role', {
                                                           ...memberRef,
                                                           role: selected
                                                         })) setEdit(undefined);
                                                       }}>
                                             <Button size="small"
                                                     disabled={locked || selected === member.role}>保存角色</Button>
                                           </Popconfirm>
                                         </>}
                                         <Popconfirm title={`移除 ${name(member)}？`} okText="确认移除"
                                                     description={space ? '该账号将失去此空间的访问权限。' : '该账号将同时失去本租户下的工作空间权限。'}
                                                     onConfirm={async () => {
                                                       if (await run('remove', memberRef)) setEdit(undefined);
                                                     }}>
                                           <Button size="small" danger disabled={locked}>移除</Button>
                                         </Popconfirm>
                                         {caps.transferOwner && caps.memberVersion != null && member.role !== 3 &&
                                           <Popconfirm title={`将负责人交接给 ${name(member)}？`} okText="确认交接"
                                                       description="交接后你的角色变为管理员。"
                                                       onConfirm={async () => {
                                                         if (await run('transfer', {
                                                           targetMemberId: member.memberId,
                                                           expectedOwnerMemberVersion: caps.memberVersion,
                                                           expectedTargetMemberVersion: member.version
                                                         })) setEdit(undefined);
                                                       }}>
                                             <Button size="small" disabled={locked}>交接负责人</Button>
                                           </Popconfirm>}
                                       </Flex>;
                                     }
                                   }
                                 ]}/>
      </Card>
      <Card title="已发邀请" size="small" extra={<Select aria-label="已发邀请状态" value={invitationState}
                                                         options={filterOptions} onChange={setInvitationState}
                                                         style={{width: 130}}/>}>
        {invitations.error && <Alert type="warning" title={invitations.error}
                                     action={<Button onClick={invitations.reload}>重新读取邀请</Button>}/>}
        <Table<MembershipInvitation> rowKey="invitationId" size="small" scroll={{x: 760}}
                                     loading={invitations.loading} pagination={invitations.pagination}
                                     dataSource={invitations.data?.records ?? []} locale={{emptyText: '暂无已发邀请'}}
                                     columns={[
                                       {
                                         title: '受邀账号',
                                         render: (_, item) => item.targetDisplayName ?? item.targetSubjectId
                                       },
                                       ...invitationColumns,
                                       {
                                         title: '操作', render: (_, item) => item.state === 0 &&
                                           <Popconfirm title="撤销这条邀请？" okText="确认撤销"
                                                       onConfirm={async () => {
                                                         await run('revoke', {
                                                           invitationId: item.invitationId,
                                                           expectedInvitationVersion: item.version
                                                         });
                                                       }}>
                                             <Button size="small" disabled={locked}>撤销邀请</Button>
                                           </Popconfirm>
                                       }
                                     ]}/>
      </Card>
    </>}
    {caps.leave && <Card title="我的成员资格" size="small">
      <Popconfirm title="退出此工作空间？" okText="确认退出"
                  description="退出后将失去该空间的访问权限，再加入需要新的邀请。"
                  onConfirm={async () => {
                    await run('leave', {expectedMemberVersion: caps.memberVersion});
                  }}>
        <Button danger disabled={locked || caps.memberVersion == null}>退出{scopeName}</Button>
      </Popconfirm>
    </Card>}
  </Flex>;
}

function InvitationForm({target, locked, onIssue}: {
  target: MembershipTarget;
  locked?: boolean;
  onIssue(subjectId: string, role: number, days: number): Promise<boolean> | undefined;
}) {
  const [account, setAccount] = useState('');
  const [found, setFound] = useState<InvitationTarget>();
  const [role, setRole] = useState(2);
  const [days, setDays] = useState(7);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState('');
  const reader = useRef<AbortController | null>(null);

  function reset() {
    reader.current?.abort();
    setSearching(false);
    setAccount('');
    setFound(undefined);
    setRole(2);
    setDays(7);
    setError('');
    return true;
  }

  useEffect(() => () => reader.current?.abort(), []);
  useConfigurationDraft({
    group: 'member-invitation',
    label: '成员邀请草稿',
    status: account ? 'dirty' : 'clean',
    discard: reset
  });

  async function find() {
    reader.current?.abort();
    const request = new AbortController();
    reader.current = request;
    setFound(undefined);
    setError('');
    setSearching(true);
    try {
      const value = await findInvitationTarget(target, account.trim(), {signal: request.signal});
      if (!request.signal.aborted) setFound(value);
    } catch {
      if (!request.signal.aborted) setError('未找到可邀请的有效账号，或当前无查询资格。');
    } finally {
      if (!request.signal.aborted) setSearching(false);
    }
  }

  return <Card title="邀请成员" size="small">
    <Flex vertical gap={8}>
      <Typography.Text
        type="secondary">{target.scope ? '邀请仅适用于此工作空间。' : '租户邀请只授予组织成员资格。'}接受后仍按具体资源权限访问。</Typography.Text>
      <Flex gap={8} wrap>
        <Input aria-label="受邀人的完整账号" placeholder="输入完整账号" style={{width: 220}} maxLength={100}
               disabled={locked} value={account} onPressEnter={() => {
          if (!locked && !searching && account.trim()) void find();
        }}
               onChange={event => {
                 reader.current?.abort();
                 setSearching(false);
                 setFound(undefined);
                 setError('');
                 setAccount(event.target.value);
               }}/>
        <Button aria-label="查找账号" loading={searching} disabled={locked || searching || !account.trim()}
                onClick={() => void find()}>查找账号</Button>
        {target.scope && <Select aria-label="邀请角色" value={role} disabled={locked}
                                 options={[2, 3].map(value => ({value, label: roles[value]}))} onChange={setRole}/>}
        <Select aria-label="邀请有效期" value={days} disabled={locked} onChange={setDays}
                options={[1, 3, 7, 14, 30].map(value => ({value, label: `${value} 天有效`}))}/>
      </Flex>
      {error && <Alert type="warning" title={error}/>}
      {found && <Flex align="center" gap={8} wrap>
        <Typography.Text>受邀账号 {found.displayName} · {roles[role]}</Typography.Text>
        <Button type="primary" disabled={locked} onClick={async () => {
          if (await onIssue(found.subjectId, role, days)) reset();
        }}>发送邀请</Button>
      </Flex>}
    </Flex>
  </Card>;
}

const invitationColumns = [
  {title: '邀请角色', render: (_: unknown, item: MembershipInvitation) => roles[item.role ?? 2]},
  {title: '状态', render: (_: unknown, item: MembershipInvitation) => states[item.state]},
  {title: '到期时间', render: (_: unknown, item: MembershipInvitation) => new Date(item.expiresAt).toLocaleString()},
];

function InvitationDetails({item}: { item: MembershipInvitation }) {
  return <Flex vertical gap={4}>
    <Typography.Text>{item.targetName ?? '目标名称暂不可用'}</Typography.Text>
    <Typography.Text
      type="secondary">{item.tenantType === 0 ? '个人托管' : item.tenantType === 1 ? '组织' : '归属待确认'} · {item.tenantName ?? '名称暂不可用'}</Typography.Text>
    {item.tenantType === 0 &&
      <Typography.Text type="secondary">托管者：{item.hostDisplayName ?? '账号暂不可用'}</Typography.Text>}
    <Typography.Text type="secondary">邀请人：{item.inviterDisplayName ?? '账号暂不可用'}</Typography.Text>
    <Typography.Text
      type="secondary">{item.sharingBoundary ?? '加入范围以邀请目标为限，资源仍需独立授权。'}</Typography.Text>
  </Flex>;
}

export function MembershipInbox({workspace, initiallyOpen = false}: { workspace: boolean; initiallyOpen?: boolean }) {
  const session = useMembershipSession();
  const screens = Grid.useBreakpoint();
  const [state, setState] = useState(0);
  const read = useMemo<ReadPage<MembershipInvitation>>(() => (page, signal) =>
    listMyInvitations(workspace, page, {signal}, state < 0 ? undefined : state), [workspace, state]);
  const inbox = usePage(read, session?.revision ?? 0);
  const [open, setOpen] = useState(initiallyOpen);
  // Pending count is read separately from the selected history filter.
  const pendingRead = useMemo<ReadPage<MembershipInvitation>>(() => (page, signal) =>
    listMyInvitations(workspace, page, {signal}, 0), [workspace]);
  const pending = usePage(state === 0 ? undefined : pendingRead, session?.revision ?? 0);
  const count = state === 0 ? inbox.data?.total : pending.data?.total;
  useEffect(() => {
    const reload = () => {
      inbox.reload();
      pending.reload();
    };
    window.addEventListener('focus', reload);
    const timer = window.setInterval(reload, 60000);
    return () => {
      window.removeEventListener('focus', reload);
      window.clearInterval(timer);
    };
  }, []);
  const decide = (item: MembershipInvitation, action: 'accept' | 'decline') => {
    const scope: WorkspaceScope | undefined = item.scope ?? undefined;
    const tenantId = scope?.tenantId ?? item.tenantId;
    return tenantId ? session?.run({
      target: {tenantId, scope}, ...(item.targetName ? {targetLabel: item.targetName} : {}), action,
      payload: {invitationId: item.invitationId, expectedInvitationVersion: item.version}
    }) : undefined;
  };
  const actions = (item: MembershipInvitation) => item.state === 0 && <Flex gap={8} wrap>
    <Popconfirm title="接受这条邀请？" okText="确认接受"
                description={<Flex vertical gap={6}><InvitationDetails item={item}/>
                  <Typography.Text>加入角色：{roles[item.role ?? 2]}；到期时间：{new Date(item.expiresAt).toLocaleString()}</Typography.Text>
                  <Typography.Text>{workspace ? '接受后在目录中找到空间，仍需显式进入。' : '接受后只取得组织成员资格。'}</Typography.Text>
                </Flex>} onConfirm={async () => {
      await decide(item, 'accept');
    }}>
      <Button size="small" type="primary" disabled={session?.locked}>接受邀请</Button>
    </Popconfirm>
    <Popconfirm title="拒绝这条邀请？" okText="确认拒绝"
                description="拒绝后不会加入，也不会改变已有成员资格；再次加入需要新的邀请。"
                onConfirm={async () => {
                  await decide(item, 'decline');
                }}>
      <Button size="small" disabled={session?.locked}>拒绝邀请</Button>
    </Popconfirm>
  </Flex>;
  return <>
    <Button size="small" aria-label={open ? '收起我的邀请' : '我的邀请'} onClick={() => setOpen(value => !value)}>
      {open ? '收起我的邀请' : '我的邀请'}{count !== undefined && ` · ${count} 条待处理`}
    </Button>
    {open && <Card title={workspace ? '我的工作空间邀请' : '我的租户邀请'} size="small"
                   extra={<Select aria-label="收到的邀请状态" value={state} options={filterOptions} onChange={setState}
                                  style={{width: 130}}/>}>
      {inbox.error &&
        <Alert type="warning" title={inbox.error} action={<Button onClick={inbox.reload}>重新读取</Button>}/>}
      {screens.md ? <Table<MembershipInvitation> rowKey="invitationId" size="small" scroll={{x: 760}}
                                                 dataSource={inbox.data?.records ?? []} loading={inbox.loading}
                                                 pagination={inbox.pagination}
                                                 locale={{emptyText: state === 0 ? '没有待处理邀请' : '没有匹配的邀请'}}
                                                 columns={[
                                                   {
                                                     title: workspace ? '工作空间与归属' : '组织与邀请人',
                                                     render: (_, item) => <InvitationDetails item={item}/>
                                                   },
                                                   ...invitationColumns.map(column => ({
                                                     ...column,
                                                     width: column.title === '到期时间' ? 180 : 90
                                                   })),
                                                   {title: '操作', width: 170, render: (_, item) => actions(item)}
                                                 ]}/> :
        <List<MembershipInvitation> loading={inbox.loading} dataSource={inbox.data?.records ?? []}
                                    pagination={inbox.pagination}
                                    locale={{emptyText: state === 0 ? '没有待处理邀请' : '没有匹配的邀请'}}
                                    renderItem={item => <List.Item style={{display: 'block'}}>
                                      <Flex vertical gap={10} style={{minWidth: 0, overflowWrap: 'anywhere'}}>
                                        <InvitationDetails item={item}/>
                                        <Typography.Text>加入角色：{roles[item.role ?? 2]} · {states[item.state]}</Typography.Text>
                                        <Typography.Text
                                          type="secondary">到期：{new Date(item.expiresAt).toLocaleString()}</Typography.Text>
                                        {actions(item)}
                                      </Flex>
                                    </List.Item>}/>}
    </Card>}
  </>;
}
