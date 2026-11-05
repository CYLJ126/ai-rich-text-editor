import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Card, Flex, Input, Popconfirm, Select, Table, Typography} from 'antd';
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
const states = ['待接受', '已接受', '已撤销', '已过期'];
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
  const [current, setCurrent] = useState(1);
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
      onChange: setCurrent
    }
  };
}

export function MembershipPanel({target, version, caps, disabled}: {
  target: MembershipTarget; version: number; caps: MembershipCapabilities; disabled?: boolean;
}) {
  const session = useMembershipSession();
  const locked = disabled || !!session?.locked;
  const members = useMemo<ReadPage<MembershipMember> | undefined>(() => caps.manage
    ? (page, signal) => listMembers(target, page, {signal}) : undefined, [target, caps.manage]);
  const invites = useMemo<ReadPage<MembershipInvitation> | undefined>(() => caps.manage
    ? (page, signal) => listInvitations(target, page, {signal}) : undefined, [target, caps.manage]);
  const directory = usePage(members, session?.revision ?? 0);
  const invitations = usePage(invites, session?.revision ?? 0);
  const [edit, setEdit] = useState<{ memberId: string; version: number; role: number }>();
  const owner = caps.assignAdmin;
  const space = !!target.scope;
  const scopeName = space ? '工作空间' : '组织租户';
  const run = (action: MembershipAction, payload: Record<string, unknown>) =>
    session?.run({target, action, expectedVersion: version, payload});
  useConfigurationDraft({
    group: 'member-role', label: '成员角色草稿', status: edit ? 'dirty' : 'clean', discard: () => {
      setEdit(undefined);
      return true;
    }
  });

  return <Flex vertical gap={12}>
    {caps.invite && <InvitationForm target={target} locked={locked} onIssue={(subjectId, role) =>
      run('issue', space ? {targetSubjectId: subjectId, role} : {targetSubjectId: subjectId})}/>}
    {caps.manage && <>
      <Card title="成员目录" size="small">
        {directory.error && <Alert type="warning" title={directory.error}
                                   action={<Button onClick={directory.reload}>重新读取成员</Button>}/>}
        <Table<MembershipMember> rowKey="memberId" size="small" scroll={{x: true}}
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
                                         {(space || member.role !== 3) && <>
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
      <Card title="已发邀请" size="small">
        {invitations.error && <Alert type="warning" title={invitations.error}
                                     action={<Button onClick={invitations.reload}>重新读取邀请</Button>}/>}
        <Table<MembershipInvitation> rowKey="invitationId" size="small" scroll={{x: true}}
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
  target: MembershipTarget; locked?: boolean; onIssue(subjectId: string, role: number): Promise<boolean> | undefined;
}) {
  const [account, setAccount] = useState('');
  const [found, setFound] = useState<InvitationTarget>();
  const [role, setRole] = useState(2);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState('');
  const reader = useRef<AbortController | null>(null);

  function reset() {
    reader.current?.abort();
    setSearching(false);
    setAccount('');
    setFound(undefined);
    setRole(2);
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
      <Typography.Text type="secondary">{target.scope ? '邀请仅适用于此工作空间。' : '租户邀请只授予组织成员资格。'}邀请有效期为
        7 天。</Typography.Text>
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
      </Flex>
      {error && <Alert type="warning" title={error}/>}
      {found && <Flex align="center" gap={8} wrap>
        <Typography.Text>受邀账号 {found.displayName} · {roles[role]}</Typography.Text>
        <Button type="primary" disabled={locked} onClick={async () => {
          if (await onIssue(found.subjectId, role)) reset();
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

export function MembershipInbox({workspace}: { workspace: boolean }) {
  const session = useMembershipSession();
  const read = useMemo<ReadPage<MembershipInvitation>>(() => (page, signal) => listMyInvitations(workspace, page, {signal}), [workspace]);
  const inbox = usePage(read, session?.revision ?? 0);
  const [open, setOpen] = useState(false);
  if (!inbox.data && !open && (!inbox.error || inbox.unavailable)) return null;
  return <>
    <Button size="small" onClick={() => setOpen(value => !value)}>
      {open ? '收起我的邀请' : '我的邀请'}
    </Button>
    {open && <Card title={workspace ? '我的工作空间邀请' : '我的租户邀请'} size="small">
      {inbox.error &&
        <Alert type="warning" title={inbox.error} action={<Button onClick={inbox.reload}>重新读取</Button>}/>}
      <Table<MembershipInvitation> rowKey="invitationId" size="small" scroll={{x: true}}
                                   dataSource={inbox.data?.records ?? []} loading={inbox.loading}
                                   pagination={inbox.pagination}
                                   locale={{emptyText: '没有收到邀请'}} columns={[
        {
          title: workspace ? '工作空间' : '租户', render: (_, item) => item.targetName ??
            (item.scope ? `${item.scope.tenantId} / ${item.scope.workspaceId}` : item.tenantId)
        },
        ...invitationColumns,
        {
          title: '操作', render: (_, item) => item.state === 0 &&
            <Popconfirm title="接受这条邀请？" okText="确认接受"
                        description={workspace ? '接受后可在目录中找到此工作空间。' : '接受后可在目录中找到此租户。'}
                        onConfirm={async () => {
                          const scope: WorkspaceScope | undefined = item.scope ?? undefined;
                          const tenantId = scope?.tenantId ?? item.tenantId;
                          if (tenantId) await session?.run({
                            target: {tenantId, scope}, action: 'accept',
                            payload: {invitationId: item.invitationId, expectedInvitationVersion: item.version}
                          });
                        }}>
              <Button size="small" type="primary" disabled={session?.locked}>接受邀请</Button>
            </Popconfirm>
        }
      ]}/>
    </Card>}
  </>;
}
