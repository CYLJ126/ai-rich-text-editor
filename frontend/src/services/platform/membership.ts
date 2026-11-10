import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {type WorkspaceScope, workspaceScopeSchema} from './workspace';

const base = '/arte/workspace';
const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const role = z.union([z.literal(0), z.literal(1), z.literal(2), z.literal(3)]);
const caps = z.object({
  available: z.boolean(), manage: z.boolean(), invite: z.boolean(), assignAdmin: z.boolean(),
  readOnly: z.boolean().optional(),
  transferOwner: z.boolean(),
  leave: z.boolean(),
  memberId: id.nullish(),
  memberVersion: version.nullish()
});
const member = z.object({
  memberId: id, subjectId: id, displayName: id.nullish(), role, state: z.union([z.literal(0), z.literal(1)]), version
});
const invitation = z.object({
  invitationId: id, tenantId: id.nullish(), scope: workspaceScopeSchema.nullish(), targetSubjectId: id,
  targetName: id.nullish(), targetDisplayName: id.nullish(),
  tenantName: id.nullish(), tenantType: z.union([z.literal(0), z.literal(1)]).nullish(),
  hostDisplayName: id.nullish(), inviterDisplayName: id.nullish(), sharingBoundary: z.string().max(1000).nullish(),
  role: role.nullish(), state: z.union([z.literal(0), z.literal(1), z.literal(2), z.literal(3), z.literal(4)]),
  expiresAt: z.iso.datetime({offset: true}), version
}).refine(value => !!value.tenantId !== !!value.scope && (value.scope ? value.role === 2 || value.role === 3 : value.role == null));
const lookup = z.object({subjectId: id, displayName: id});
const page = <T extends z.ZodType>(value: T) => z.object({
  records: z.array(value).max(100), current: version.min(1), size: version.min(1).max(100), total: version
});
const target = z.object({tenantId: id, scope: workspaceScopeSchema.optional()})
  .refine(value => !value.scope || value.scope.tenantId === value.tenantId);
export type MembershipTarget = z.infer<typeof target>;
export type MembershipCapabilities = z.infer<typeof caps>;
export type MembershipMember = z.infer<typeof member>;
export type MembershipInvitation = z.infer<typeof invitation>;
export type InvitationTarget = z.infer<typeof lookup>;

const endpoints = {
  tenant: {
    issue: 'inviteTenantMember',
    revoke: 'revokeTenantInvitation',
    accept: 'acceptTenantInvitation',
    decline: 'declineTenantInvitation',
    role: 'changeTenantMemberRole', remove: 'removeTenantMember', transfer: 'transferTenantOwner'
  },
  workspace: {
    issue: 'inviteWorkspaceMember',
    revoke: 'revokeWorkspaceInvitation',
    accept: 'acceptWorkspaceInvitation', decline: 'declineWorkspaceInvitation',
    role: 'changeWorkspaceMemberRole',
    remove: 'removeWorkspaceMember',
    transfer: 'transferWorkspaceOwner',
    leave: 'leaveWorkspace'
  }
} as const;
const action = z.enum(['issue', 'revoke', 'accept', 'decline', 'role', 'remove', 'transfer', 'leave']);
const data = {
  issue: z.object({
    targetSubjectId: id,
    role: z.union([z.literal(2), z.literal(3)]).optional(),
    expiresAt: z.iso.datetime({offset: true}).optional()
  }).strict(),
  revoke: z.object({invitationId: id, expectedInvitationVersion: version}).strict(),
  accept: z.object({invitationId: id, expectedInvitationVersion: version}).strict(),
  decline: z.object({invitationId: id, expectedInvitationVersion: version}).strict(),
  role: z.object({
    memberId: id,
    expectedMemberVersion: version,
    role: z.union([z.literal(1), z.literal(2), z.literal(3)])
  }).strict(),
  remove: z.object({memberId: id, expectedMemberVersion: version}).strict(),
  transfer: z.object({
    targetMemberId: id,
    expectedOwnerMemberVersion: version,
    expectedTargetMemberVersion: version
  }).strict(),
  leave: z.object({expectedMemberVersion: version}).strict(),
};
export const membershipCommandSchema = z.object({
  target,
  action,
  targetLabel: z.string().min(1).max(100).optional(),
  expectedVersion: version.optional(),
  payload: z.record(z.string(), z.unknown())
}).strict().superRefine((value, context) => {
  const valid = data[value.action].safeParse(value.payload).success
    && (['accept', 'decline'].includes(value.action) ? value.expectedVersion === undefined : value.expectedVersion !== undefined)
    && (value.action !== 'leave' || !!value.target.scope)
    && (value.action !== 'issue' || (value.target.scope ? value.payload.role !== undefined : value.payload.role === undefined))
    && (value.action !== 'role' || !!value.target.scope || value.payload.role !== 3);
  if (!valid) context.addIssue({code: 'custom', message: '成员命令格式或范围不一致'});
});
export type MembershipCommand = z.infer<typeof membershipCommandSchema>;
export type MembershipAction = MembershipCommand['action'];
const receipt = z.object({
  operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]), resultVersion: version,
  value: z.unknown(), acceptedAt: z.string(), completedAt: z.string().nullable()
});
export type MembershipReceipt = z.infer<typeof receipt>;
const join = z.object({
  outcome: z.union([z.literal(0), z.literal(1)]), tenantId: id.optional(), scope: workspaceScopeSchema.optional(),
  member: member.omit({displayName: true})
});
const transferred = z.object({
  id: id.optional(),
  scope: workspaceScopeSchema.optional(),
  currentRole: z.literal(1),
  version
});
const sameScope = (a?: WorkspaceScope | null, b?: WorkspaceScope | null) =>
  a?.tenantId === b?.tenantId && a?.workspaceId === b?.workspaceId;
const invalid = (status: number) => new AiApiError(status, {code: 'INVALID_MEMBERSHIP_RESPONSE'}, '成员治理响应无法核验');

async function read<T extends z.ZodType>(path: string, input: unknown, schema: T, options?: AiRequestOptions) {
  const response = await postResult<unknown>(path, input, options, undefined, base);
  const parsed = schema.safeParse(response.body.data);
  if (!parsed.success) throw invalid(response.httpStatus);
  return parsed.data as z.output<T>;
}

const located = (value: MembershipTarget) => value.scope ? {scope: value.scope} : {tenantId: value.tenantId};
export const membershipCapabilities = (value: MembershipTarget, options?: AiRequestOptions) =>
  read('getMembershipCapabilities', value, caps, options);
export const findInvitationTarget = (value: MembershipTarget, account: string, options?: AiRequestOptions) =>
  read('findInvitationTarget', {...value, account}, lookup.refine(item => item.displayName === account), options);
export const listMembers = (value: MembershipTarget, current: number, options?: AiRequestOptions) =>
  read(value.scope ? 'listWorkspaceMembers' : 'listTenantMembers', {...located(value), current}, page(member), options);
const sameTarget = (value: {
  tenantId?: string | null;
  scope?: WorkspaceScope | null
}, selected: MembershipTarget) => selected.scope
  ? !value.tenantId && sameScope(value.scope, selected.scope) : !value.scope && value.tenantId === selected.tenantId;
export const listInvitations = (value: MembershipTarget, current: number, options?: AiRequestOptions, state?: number) =>
  read(value.scope ? 'listWorkspaceInvitations' : 'listTenantInvitations', {
      ...located(value),
      current, ...(state === undefined ? {} : {state})
    },
    page(invitation.refine(item => sameTarget(item, value))), options);
export const listMyInvitations = (workspace: boolean, current: number, options?: AiRequestOptions, state?: number) =>
  read(workspace ? 'listMyWorkspaceInvitations' : 'listMyTenantInvitations', {current, ...(state === undefined ? {} : {state})},
    page(invitation.refine(item => !!item.scope === workspace)), options);

function validateReceipt(input: unknown, status: number, command: MembershipCommand, expectedId?: string) {
  const value = receipt.safeParse(input);
  if (!value.success || expectedId && (value.data.operationId !== expectedId || value.data.delivery !== 1)) throw invalid(status);
  const result = value.data;
  const {action, target, payload, expectedVersion} = command;
  let valid = false;
  if (action === 'issue' || action === 'revoke' || action === 'decline') {
    const item = invitation.safeParse(result.value);
    valid = item.success && sameTarget(item.data, target) && item.data.version === result.resultVersion
      && (action === 'issue' ? item.data.targetSubjectId === payload.targetSubjectId && item.data.role === payload.role
        && item.data.version === 0 && item.data.state === 0
        : item.data.invitationId === payload.invitationId && item.data.state === (action === 'decline' ? 4 : 2)
        && item.data.version === Number(payload.expectedInvitationVersion) + 1);
  } else if (action === 'accept') {
    const item = join.safeParse(result.value);
    valid = item.success && sameTarget(item.data, target) && item.data.member.version === result.resultVersion && item.data.member.state === 0;
  } else if (action === 'role') {
    const item = member.safeParse(result.value);
    valid = item.success && item.data.memberId === payload.memberId && item.data.role === payload.role
      && item.data.version === result.resultVersion && item.data.state === 0
      && result.resultVersion === Number(payload.expectedMemberVersion) + 1;
  } else if (action === 'transfer') {
    const item = transferred.safeParse(result.value);
    valid = item.success && sameTarget({...item.data, tenantId: item.data.id}, target)
      && item.data.version === result.resultVersion && result.resultVersion === Number(expectedVersion) + 1;
  } else {
    valid = result.value == null && result.resultVersion === Number(target.scope ? payload.expectedMemberVersion : expectedVersion) + 1;
  }
  if (!valid) throw invalid(status);
  return result;
}

export async function executeMembership(command: MembershipCommand, key: string) {
  requireIdempotencyKey(key);
  const request = membershipCommandSchema.parse(command);
  const {action, target, payload} = request;
  const endpoint = target.scope ? endpoints.workspace[action] : action !== 'leave' ? endpoints.tenant[action] : null;
  if (!endpoint) throw new Error('无效成员操作');
  const parent = target.scope ? 'expectedWorkspaceVersion' : 'expectedTenantVersion';
  const body = action === 'accept' || action === 'decline' ? payload : {
    ...located(target),
    [parent]: request.expectedVersion, ...payload
  };
  const response = await postResult<unknown>(endpoint, body, undefined, key, base);
  return validateReceipt(response.body.data, response.httpStatus, request);
}

export async function queryMembershipReceipt(command: MembershipCommand, operationId: string, options?: AiRequestOptions) {
  const response = await postResult<unknown>('getWorkspaceCommandReceipt', {operationId}, options, undefined, base);
  return validateReceipt(response.body.data, response.httpStatus, command, operationId);
}

/** Counts include only this account's unexpired pending invitations, across all pages. */
export const getMyPendingInvitationCounts = (options?: AiRequestOptions) =>
  read('getMyPendingInvitationCounts', undefined, z.object({tenant: version, workspace: version}), options);
