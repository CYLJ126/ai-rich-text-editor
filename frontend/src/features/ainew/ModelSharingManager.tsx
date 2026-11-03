import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Checkbox, Flex, Input, Popconfirm, Select, Table, Typography,} from 'antd';
import {AiApiError} from '@/services/arte-ai/request';
import {listManagedModels, type ManagedModel,} from '@/services/platform/model';
import {browseAccessibleWorkspaces, type WorkspaceScope,} from '@/services/platform/workspace';
import * as api from '@/services/platform/modelSharing';
import type {BrowserContext} from './storage';
import {
  newSharingOperation,
  readSharingOperation,
  saveSharingOperation,
  type SharingOperation,
} from './modelSharingRecovery';

type Space = { scope: WorkspaceScope; name: string };
type Props = {
  context: BrowserContext;
  model?: ManagedModel;
  embedded?: boolean;
  disabled?: boolean;
  onCreated?: (resourceId: string) => void;
  onUnsafeChange?: (value: boolean) => void;
};
const actionNames: Record<string, string> = {
  'model:read': '读取',
  'model:edit': '编辑',
  'model:validate': '验证',
  'model:publish': '发布',
  'model:use': '使用',
  'model:disable': '停用',
  'model:share': '分享',
  'resource:access:manage': '管理授权',
};
export default function ModelSharingManager(props: Props) {
  return (
    <Manager
      key={JSON.stringify([props.context.userId, props.context.scope])}
      {...props}
    />
  );
}

function Manager({context, onUnsafeChange, model: suppliedModel, embedded, disabled, onCreated}: Props) {
  const [restored] = useState(() => readSharingOperation(context));
  const operation = useRef<SharingOperation | null>(restored.operation);
  const initial = restored.operation?.command;
  const originalRule =
    initial?.kind === 'grant'
      ? initial.request
      : initial?.kind === 'visibility'
        ? initial.request.sharedGrants[0]
        : undefined;
  const initialId =
    initial && initial.kind !== 'create'
      ? 'target' in initial.request
        ? initial.request.target.resourceId
        : initial.request.resourceId
      : '';
  const [pending, setPending] = useState(operation.current);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [records, setRecords] = useState<ManagedModel[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [selected, setSelected] = useState(initialId || suppliedModel?.resourceId || '');
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceCursor, setSpaceCursor] = useState<string | null>(null);
  const [members, setMembers] = useState<
    { subjectId: string; role: string; state: string }[]
  >([]);
  const [memberPage, setMemberPage] = useState(0);
  const [moreMembers, setMoreMembers] = useState(true);
  const [sharing, setSharing] = useState<api.SharingView>();
  const [access, setAccess] = useState<api.ModelAccess>();
  const [grants, setGrants] = useState<api.ModelGrant[]>([]);
  const [grantPage, setGrantPage] = useState(1);
  const [grantTotal, setGrantTotal] = useState(0);
  const [aclWorkspace, setAclWorkspace] = useState(
    initial && 'target' in initial.request
      ? initial.request.target.scope.workspaceId
      : context.scope.workspaceId,
  );
  const [workspaceIds, setWorkspaceIds] = useState<string[]>(
    initial?.kind === 'scopes' ? initial.request.workspaceIds : [],
  );
  const [subjectIds, setSubjectIds] = useState<string[]>(
    initial?.kind === 'consumers' ? initial.request.subjectIds : [],
  );
  const [name, setName] = useState(
    initial?.kind === 'create' ? initial.request.name : '',
  );
  const [recipient, setRecipient] = useState(
    originalRule?.recipient.kind === 'SUBJECT'
      ? originalRule.recipient.subjectId
      : '',
  );
  const [recipientKind, setRecipientKind] = useState<
    'SUBJECT' | 'SPACE_ROLE' | 'SPACE'
  >(originalRule?.recipient.kind ?? 'SUBJECT');
  const [role, setRole] = useState<'OWNER' | 'ADMIN' | 'MEMBER' | 'GUEST'>(
    originalRule?.recipient.kind === 'SPACE_ROLE'
      ? originalRule.recipient.role
      : 'MEMBER',
  );
  const [actions, setActions] = useState<
    (typeof api.modelGrantActions)[number][]
  >(originalRule?.actions ?? ['model:read', 'model:use']);
  const [expiry, setExpiry] = useState(originalRule?.expiresAt ?? '');
  const [dirtyScopes, setDirtyScopes] = useState(initial?.kind === 'scopes');
  const [dirtyConsumers, setDirtyConsumers] = useState(
    initial?.kind === 'consumers',
  );
  const [dirtyAcl, setDirtyAcl] = useState(
    !!initial && ['grant', 'revoke', 'visibility'].includes(initial.kind),
  );
  const dirty = useRef({scopes: false, consumers: false});
  dirty.current = {scopes: dirtyScopes, consumers: dirtyConsumers};
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [stale, setStale] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const live = useRef(true);
  const reader = useRef<AbortController | null>(null);
  const writing = useRef(false);
  const unsafe =
    !!pending ||
    blocked ||
    saving ||
    dirtyScopes ||
    dirtyConsumers ||
    dirtyAcl ||
    !!name;
  const locked = disabled || loading || saving || !!pending || blocked;
  const model = suppliedModel?.resourceId === selected ? suppliedModel : records.find((record) => record.resourceId === selected);
  useEffect(() => {
    if (embedded && !operation.current && !blocked && !saving && !dirtyScopes && !dirtyConsumers && !dirtyAcl) setSelected(suppliedModel?.resourceId ?? '');
  }, [embedded, suppliedModel?.resourceId]);
  const aclScope = {
    tenantId: context.scope.tenantId,
    workspaceId: aclWorkspace,
  };
  const target = {
    scope: aclScope,
    resourceType: 'model' as const,
    resourceId: selected,
  };
  useEffect(() => {
    onUnsafeChange?.(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (unsafe) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsafe]);

  function failure(value: unknown) {
    if (value instanceof AiApiError) {
      if (value.httpStatus === 401)
        return '登录已失效，请重新登录后确认原授权操作。';
      if (value.code === 'PLATFORM_IDEMPOTENCY_CONFLICT')
        return '原操作参数不一致，请保留原记录核对，不能换键重复提交。';
      if (value.httpStatus === 403)
        return '当前无权执行此项治理；模型权限、租户资格和凭据责任分别核验。';
      if (value.httpStatus === 404)
        return '目标不可访问或当前部署尚未启用租户模型共享；请核对范围、开关及迁移。';
      if (value.httpStatus === 409)
        return '版本已变化，草稿已保留。请重新读取并核对后再明确提交。';
      if (value.httpStatus === 400)
        return '请检查授权动作、接收成员、空间范围、到期时间和当前凭据版本。';
    }
    return '请求结果暂时无法确认，请保留原操作并使用原键重试确认。';
  }

  async function models(after: string | null = null) {
    try {
      const result = await listManagedModels(context.scope, after);
      if (!live.current) return;
      setRecords((previous) =>
        after
          ? [
            ...previous,
            ...result.body.data.records.filter(
              (r) => !previous.some((p) => p.resourceId === r.resourceId),
            ),
          ]
          : result.body.data.records,
      );
      setCursor(result.body.data.nextCursor);
    } catch (value) {
      if (live.current) setError(failure(value));
    }
  }

  async function directory(after: string | null = null) {
    try {
      const result = await browseAccessibleWorkspaces(after);
      if (!live.current) return;
      const sameTenant = result.body.data.records.filter(
        (space) => space.scope.tenantId === context.scope.tenantId,
      );
      setSpaces((previous) =>
        after
          ? [
            ...previous,
            ...sameTenant.filter(
              (s) =>
                !previous.some(
                  (p) => p.scope.workspaceId === s.scope.workspaceId,
                ),
            ),
          ]
          : sameTenant,
      );
      setSpaceCursor(result.body.data.nextCursor);
    } catch (value) {
      if (live.current) setError(failure(value));
    }
  }

  async function loadMembers(page = 1) {
    try {
      const result = await api.listTenantModelMembers(context.scope, page);
      if (!live.current) return;
      setMembers((previous) =>
        page === 1
          ? result.records
          : [
            ...previous,
            ...result.records.filter(
              (m) => !previous.some((p) => p.subjectId === m.subjectId),
            ),
          ],
      );
      setMemberPage(page);
      setMoreMembers(page * result.size < result.total);
    } catch (value) {
      if (live.current) {
        setMoreMembers(false);
        setNotice(
          `成员目录暂不可用：${failure(value)} 可使用已知稳定主体 ID，保存时仍核验当前成员。`,
        );
      }
    }
  }

  async function load(id = selected, scope = aclScope, page = 1) {
    reader.current?.abort();
    const controller = new AbortController();
    reader.current = controller;
    setLoading(true);
    setError('');
    const values = await Promise.allSettled([
      api.getModelAccess(scope, id, {signal: controller.signal}),
      api.listModelGrants(scope, id, page, {signal: controller.signal}),
    ]);
    if (!live.current || controller.signal.aborted) return false;
    if (values[0].status === 'fulfilled') setAccess(values[0].value);
    else {
      setAccess(undefined);
      setError(failure(values[0].reason));
    }
    if (values[1].status === 'fulfilled') {
      setGrants(values[1].value.records);
      setGrantTotal(values[1].value.total);
      setGrantPage(page);
    } else {
      setGrants([]);
      setGrantTotal(0);
    }
    let policyOk = true;
    const current = suppliedModel?.resourceId === id ? suppliedModel : records.find((r) => r.resourceId === id);
    if (current?.ownershipType === 'TENANT') {
      try {
        const value = await api.getModelSharing(context.scope, id, {
          signal: controller.signal,
        });
        if (!live.current || controller.signal.aborted) return false;
        setSharing(value);
        if (!dirty.current.scopes) setWorkspaceIds(value.policy.workspaceIds);
        if (!dirty.current.consumers) setSubjectIds(value.policy.subjectIds);
      } catch (value) {
        if (live.current && !controller.signal.aborted) {
          setSharing(undefined);
          setError(failure(value));
          policyOk = false;
        }
      }
    } else setSharing(undefined);
    if (live.current && !controller.signal.aborted) {
      setLoading(false);
      setStale(false);
    }
    return values[0].status === 'fulfilled' && policyOk;
  }

  useEffect(() => {
    live.current = true;
    if (!embedded) void models();
    void directory();
    void loadMembers();
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== context.userId) return;
      live.current = false;
      reader.current?.abort();
      setAccess(undefined);
      setSharing(undefined);
      setGrants([]);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      reader.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, []);
  useEffect(() => {
    if (selected) void load();
    else {
      setAccess(undefined);
      setSharing(undefined);
    }
  }, [selected, aclWorkspace, model?.ownershipType]);

  async function run(command?: api.SharingCommand) {
    if (
      !live.current || disabled ||
      writing.current ||
      blocked ||
      (command && operation.current)
    )
      return;
    const original =
      operation.current ??
      (command ? newSharingOperation(context, command) : null);
    if (!original) return;
    if (!saveSharingOperation(context, original)) {
      setBlocked(true);
      return;
    }
    operation.current = original;
    setPending(original);
    setSaving(true);
    writing.current = true;
    setError('');
    setNotice('');
    try {
      const created = await api.executeSharingCommand(
        original.command,
        original.key,
      );
      if (!live.current) return;
      operation.current = null;
      setPending(null);
      setBlocked(!saveSharingOperation(context, null));
      const kind = original.command.kind;
      if (kind === 'scopes') {
        dirty.current.scopes = false;
        setDirtyScopes(false);
      }
      if (kind === 'consumers') {
        dirty.current.consumers = false;
        setDirtyConsumers(false);
      }
      if (['grant', 'revoke', 'visibility'].includes(kind)) setDirtyAcl(false);
      if (created) {
        setName('');
        setSelected(created.resourceId);
        onCreated?.(created.resourceId);
        if (!embedded) await models();
        setNotice(
          '原租户创建操作已确认，已在统一详情定位；请继续保存草稿、密钥并验证发布。',
        );
      } else {
        const fresh = await load();
        if (live.current)
          setNotice(
            fresh
              ? '操作已确认保存，已读取当前权限和版本。'
              : '操作已确认保存，但最新内容读取失败；只需重新读取，不要重复提交。',
          );
      }
    } catch (value) {
      if (!live.current) return;
      const uncertain =
        !(value instanceof AiApiError) ||
        value.httpStatus >= 500 ||
        value.httpStatus < 300 ||
        [401, 403, 408].includes(value.httpStatus) ||
        value.code === 'PLATFORM_IDEMPOTENCY_CONFLICT';
      if (!uncertain) {
        operation.current = null;
        setPending(null);
        setBlocked(!saveSharingOperation(context, null));
      }
      if (value instanceof AiApiError && value.httpStatus === 409)
        setStale(true);
      if (
        value instanceof AiApiError &&
        [401, 403, 404].includes(value.httpStatus)
      ) {
        setAccess(undefined);
        setSharing(undefined);
      }
      setError(failure(value));
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  function submit(command: api.SharingCommand) {
    const parsed = api.sharingCommandSchema.safeParse(command);
    if (!parsed.success) {
      setError('请填写有效接收成员、明确动作及 ISO 到期时间；不接受通配符。');
      return;
    }
    void run(parsed.data);
  }

  const spaceOptions = [
    ...new Set([
      ...spaces.map((s) => s.scope.workspaceId),
      ...workspaceIds,
      context.scope.workspaceId,
      aclWorkspace,
    ]),
  ].map((value) => ({
    value,
    label:
      spaces.find((s) => s.scope.workspaceId === value)?.name ??
      `已配置空间 · ${value}`,
  }));
  const memberOptions = [
    ...new Set([
      ...members.filter((m) => m.state === 'ACTIVE').map((m) => m.subjectId),
      ...subjectIds,
    ]),
  ].map((value) => ({
    value,
    label: `${members.find((m) => m.subjectId === value)?.role ?? '成员'} · ${value}`,
  }));
  return (
    <Flex vertical gap={16} aria-label="模型授权与使用范围">
      <Typography.Paragraph>
        模型动作、允许使用的空间、租户凭据用途分别保存。个人密钥始终仅限责任主体，授权不会创建预算或改变执行归属。
      </Typography.Paragraph>
      {error && <Alert type="warning" showIcon title={error}/>}{' '}
      {notice && <Alert type="info" showIcon title={notice}/>}
      {blocked && (
        <Alert
          type="warning"
          title="恢复记录不可用，已阻止新操作；请保留本页并检查浏览器存储。"
        />
      )}
      {pending && (
        <Card size="small" title="待确认授权操作">
          <Typography.Paragraph>
            {pending.command.kind} ·
            保留原范围、字段和操作键，刷新后不自动提交。
          </Typography.Paragraph>
          <Button
            aria-label="重试确认原授权操作"
            loading={saving}
            disabled={blocked}
            onClick={() => void run()}
          >
            重试确认原授权操作
          </Button>
        </Card>
      )}

      <Flex gap={8} wrap>
        {!embedded && <Select
          aria-label="选择授权模型"
          value={selected || undefined}
          disabled={
            locked || dirtyScopes || dirtyConsumers || dirtyAcl || !!name
          }
          style={{minWidth: 260, maxWidth: '100%'}}
          options={records.map((r) => ({
            value: r.resourceId,
            label: `${r.name} · ${r.ownershipType === 'TENANT' ? '租户' : '空间'}模型`,
          }))}
          onChange={setSelected}
        />}
        <Button
          disabled={locked}
          onClick={() => {
            void models();
            if (selected) void load();
          }}
        >
          重新读取并保留草稿
        </Button>
        {!embedded && cursor && (
          <Button disabled={locked} onClick={() => void models(cursor)}>
            继续查找模型
          </Button>
        )}
        {spaceCursor && (
          <Button disabled={locked} onClick={() => void directory(spaceCursor)}>
            继续查找空间
          </Button>
        )}
        {moreMembers && (
          <Button
            disabled={locked}
            onClick={() => void loadMembers(memberPage + 1)}
          >
            继续读取成员
          </Button>
        )}
      </Flex>
      {embedded && !selected && !pending &&
        <Typography.Text>选择模型后在此维护授权；创建入口已合并到上方模型管理。</Typography.Text>}
      {sharing && (
        <Card size="small" title="租户模型使用范围与租户凭据用途">
          <Typography.Paragraph>
            策略版本 {sharing.policy.version}
            。范围准入不授予模型动作，凭据用途只适用于当前指定代次。
          </Typography.Paragraph>
          <Flex vertical gap={10}>
            <Select
              aria-label="允许使用的空间"
              mode="multiple"
              value={workspaceIds}
              options={spaceOptions}
              disabled={locked || !sharing.mayManageScopes}
              onChange={(values) => {
                setWorkspaceIds(values);
                setDirtyScopes(true);
              }}
            />
            <Button
              disabled={locked || stale || !sharing.mayManageScopes || !dirtyScopes}
              onClick={() =>
                submit({
                  kind: 'scopes',
                  request: {
                    scope: context.scope,
                    resourceId: selected,
                    expectedVersion: sharing.policy.version,
                    workspaceIds,
                  },
                })
              }
            >
              保存允许使用的空间
            </Button>
            <Typography.Text>
              凭据：{sharing.credential.kind} · {sharing.credential.state} ·
              当前代次 {sharing.credential.version}
            </Typography.Text>
            {sharing.policy.credentialVersion !== sharing.credential.version &&
              !!sharing.policy.subjectIds.length && (
                <Alert
                  type="warning"
                  title="名单绑定旧凭据代次，当前不授权；须由责任主体重新审批。"
                />
              )}
            <Select
              aria-label="允许使用租户凭据的成员"
              mode="multiple"
              value={subjectIds}
              options={memberOptions}
              disabled={
                locked ||
                !sharing.credential.mayMaintain ||
                sharing.credential.kind !== 'TENANT' ||
                sharing.credential.state !== 'CONFIGURED'
              }
              onChange={(values) => {
                setSubjectIds(values);
                setDirtyConsumers(true);
              }}
            />
            <Button
              disabled={
                locked ||
                stale ||
                !dirtyConsumers ||
                !sharing.credential.mayMaintain ||
                sharing.credential.kind !== 'TENANT' ||
                sharing.credential.state !== 'CONFIGURED'
              }
              onClick={() =>
                submit({
                  kind: 'consumers',
                  request: {
                    scope: context.scope,
                    resourceId: selected,
                    expectedVersion: sharing.policy.version,
                    credentialVersion: sharing.credential.version,
                    subjectIds,
                  },
                })
              }
            >
              保存租户凭据用途
            </Button>
            {sharing.policy.credentialVersion !== sharing.credential.version &&
              sharing.credential.mayMaintain &&
              sharing.credential.kind === 'TENANT' &&
              sharing.credential.state === 'CONFIGURED' && (
                <Popconfirm
                  title="确认将当前名单重新授权给新的凭据代次？"
                  onConfirm={() =>
                    submit({
                      kind: 'consumers',
                      request: {
                        scope: context.scope,
                        resourceId: selected,
                        expectedVersion: sharing.policy.version,
                        credentialVersion: sharing.credential.version,
                        subjectIds,
                      },
                    })
                  }
                >
                  <Button disabled={locked || stale}>
                    以当前代次重新审批名单
                  </Button>
                </Popconfirm>
              )}
            <Typography.Text type="secondary">
              只有凭据责任主体能修改用途名单。撤销、轮换后旧代次授权不自动恢复；还需要模型
              ACL、目标空间资格及兼容预算。
            </Typography.Text>
          </Flex>
        </Card>
      )}
      {selected && (
        <Card size="small" title="模型动作授权">
          <Flex vertical gap={10}>
            <Typography.Text>
              角色和空间共享针对所选授权空间；租户模型还须在上方允许该空间。
            </Typography.Text>
            <Select
              aria-label="授权所在空间"
              value={aclWorkspace}
              options={
                model?.ownershipType === 'TENANT'
                  ? spaceOptions
                  : spaceOptions.filter(
                    (s) => s.value === context.scope.workspaceId,
                  )
              }
              disabled={locked || dirtyAcl || dirtyScopes || dirtyConsumers}
              onChange={setAclWorkspace}
            />
            {access && (
              <>
                <Typography.Text>
                  访问模式 {access.visibility} · ACL 版本 {access.aclVersion}
                </Typography.Text>
                <Select
                  aria-label="授权目标类型"
                  value={recipientKind}
                  disabled={locked}
                  options={[
                    {value: 'SUBJECT', label: '指定成员'},
                    {value: 'SPACE_ROLE', label: '空间角色'},
                    {value: 'SPACE', label: '整个空间'},
                  ]}
                  onChange={(value) => {
                    setRecipientKind(value);
                    setDirtyAcl(true);
                  }}
                />
                {recipientKind === 'SUBJECT' && (
                  <Input
                    aria-label="接收成员稳定主体 ID"
                    value={recipient}
                    disabled={locked}
                    onChange={(e) => {
                      setRecipient(e.target.value);
                      setDirtyAcl(true);
                    }}
                  />
                )}
                {recipientKind === 'SUBJECT' && !!members.length && (
                  <Select
                    aria-label="从租户成员选择接收方"
                    value={recipient || undefined}
                    options={memberOptions}
                    disabled={locked}
                    onChange={(value) => {
                      setRecipient(value);
                      setDirtyAcl(true);
                    }}
                  />
                )}
                {recipientKind === 'SPACE_ROLE' && (
                  <Select
                    aria-label="共享角色"
                    value={role}
                    options={['OWNER', 'ADMIN', 'MEMBER', 'GUEST'].map(
                      (value) => ({value}),
                    )}
                    disabled={locked}
                    onChange={(value) => {
                      setRole(value);
                      setDirtyAcl(true);
                    }}
                  />
                )}
                <Checkbox.Group
                  aria-label="授予的模型动作"
                  value={actions}
                  disabled={locked}
                  options={api.modelGrantActions.map((value) => ({
                    value,
                    label: actionNames[value],
                  }))}
                  onChange={(values) => {
                    setActions(values);
                    setDirtyAcl(true);
                  }}
                />
                <Input
                  aria-label="授权到期时间"
                  value={expiry}
                  placeholder="可选 ISO 时间，例如 2027-01-01T00:00:00Z"
                  disabled={locked}
                  onChange={(e) => {
                    setExpiry(e.target.value);
                    setDirtyAcl(true);
                  }}
                />
                <Popconfirm
                  title={
                    recipientKind === 'SUBJECT'
                      ? '确认授予这些动作？不会转交凭据用途。'
                      : '替换现有模式共享规则？独立主体授权保留。'
                  }
                  onConfirm={() => {
                    const rule = {
                      recipient:
                        recipientKind === 'SUBJECT'
                          ? {kind: 'SUBJECT' as const, subjectId: recipient}
                          : recipientKind === 'SPACE_ROLE'
                            ? {kind: 'SPACE_ROLE' as const, role}
                            : {kind: 'SPACE' as const},
                      actions,
                      ...(expiry ? {expiresAt: expiry} : {}),
                    };
                    submit(
                      recipientKind === 'SUBJECT'
                        ? {
                          kind: 'grant',
                          request: {
                            target,
                            expectedAclVersion: access.aclVersion,
                            ...rule,
                          },
                        }
                        : {
                          kind: 'visibility',
                          request: {
                            target,
                            expectedAclVersion: access.aclVersion,
                            visibility: 'SPACE_SHARED',
                            sharedGrants: [rule],
                          },
                        },
                    );
                  }}
                >
                  <Button
                    disabled={
                      locked ||
                      stale ||
                      !actions.length ||
                      (recipientKind === 'SUBJECT' && !recipient)
                    }
                  >
                    保存模型动作授权
                  </Button>
                </Popconfirm>
                <Popconfirm
                  title="关闭模式共享？所有独立主体授权保留。"
                  onConfirm={() =>
                    submit({
                      kind: 'visibility',
                      request: {
                        target,
                        expectedAclVersion: access.aclVersion,
                        visibility: 'EXPLICIT',
                        sharedGrants: [],
                      },
                    })
                  }
                >
                  <Button
                    disabled={
                      locked || stale || access.visibility !== 'SPACE_SHARED'
                    }
                  >
                    关闭模式共享
                  </Button>
                </Popconfirm>
                <Table
                  rowKey="id"
                  size="small"
                  pagination={false}
                  scroll={{x: true}}
                  dataSource={grants}
                  columns={[
                    {
                      title: '接收方',
                      render: (_, g) =>
                        g.recipient.subjectId ??
                        `${g.recipient.scope?.workspaceId ?? ''} · ${g.recipient.role ?? '整个空间'}`,
                    },
                    {title: '动作', dataIndex: 'action'},
                    {title: '状态', dataIndex: 'state'},
                    {title: '到期', dataIndex: 'expiresAt'},
                    {
                      title: '操作',
                      render: (_, g) => (
                        <Popconfirm
                          title="撤销此条独立授权？其他允许来源保留。"
                          onConfirm={() =>
                            submit({
                              kind: 'revoke',
                              request: {
                                target,
                                grantId: g.id,
                                expectedAclVersion: access.aclVersion,
                                expectedGrantVersion: g.version,
                              },
                            })
                          }
                        >
                          <Button
                            danger
                            disabled={locked || stale || g.state !== 'ACTIVE'}
                          >
                            撤销
                          </Button>
                        </Popconfirm>
                      ),
                    },
                  ]}
                />
                <Flex gap={8}>
                  <Button
                    disabled={locked || grantPage <= 1}
                    onClick={() => void load(selected, aclScope, grantPage - 1)}
                  >
                    上一页授权
                  </Button>
                  <Button
                    disabled={locked || grantPage * 20 >= grantTotal}
                    onClick={() => void load(selected, aclScope, grantPage + 1)}
                  >
                    下一页授权
                  </Button>
                </Flex>
              </>
            )}
          </Flex>
        </Card>
      )}
      {(dirtyScopes || dirtyConsumers || dirtyAcl) && (
        <Button
          disabled={locked}
          onClick={() => {
            setDirtyScopes(false);
            setDirtyConsumers(false);
            setDirtyAcl(false);
            dirty.current = {scopes: false, consumers: false};
            setRecipient('');
            setExpiry('');
            if (selected) void load();
          }}
        >
          放弃未保存授权草稿并重读
        </Button>
      )}
    </Flex>
  );
}
