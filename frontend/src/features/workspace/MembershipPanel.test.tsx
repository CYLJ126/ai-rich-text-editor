import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {
  executeMembership,
  findInvitationTarget,
  listInvitations,
  listMembers,
  listMyInvitations,
  type MembershipCapabilities,
  type MembershipInvitation,
  type MembershipReceipt
} from '@/services/platform/membership';
import {MembershipInbox, MembershipPanel} from './MembershipPanel';
import {MembershipSession, useMembershipSession} from './MembershipSession';

vi.mock('@/services/platform/membership', async original => ({
  ...await original<typeof import('@/services/platform/membership')>(),
  executeMembership: vi.fn(),
  findInvitationTarget: vi.fn(),
  listInvitations: vi.fn(),
  listMembers: vi.fn(),
  listMyInvitations: vi.fn()
}));

const scope = {tenantId: 'team', workspaceId: 'engineering'};
const target = {tenantId: scope.tenantId, scope};
const caps: MembershipCapabilities = {
  available: true, manage: true, invite: true, assignAdmin: false, transferOwner: false, leave: true,
  memberId: 'me', memberVersion: 2
};
const receipt: MembershipReceipt = {
  operationId: 'operation', status: 1, delivery: 0, resultVersion: 0, value: {},
  acceptedAt: '2026-11-05T12:00:00Z', completedAt: '2026-11-05T12:00:00Z'
};
const invitation: MembershipInvitation = {
  invitationId: 'invite', scope, targetSubjectId: 'alice', targetName: '研发空间', role: 2, state: 0,
  expiresAt: '2026-11-12T12:00:00Z', version: 0
};
const page = <T, >(records: T[]) => ({records, current: 1, size: 20, total: records.length});

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(listInvitations).mockResolvedValue(page([]));
  vi.mocked(listMembers).mockResolvedValue(page([
    {memberId: 'owner', subjectId: 'lead', displayName: '负责人账号', role: 0, state: 0, version: 0},
    {memberId: 'other-admin', subjectId: 'admin2', displayName: '另一管理员', role: 1, state: 0, version: 0},
    {memberId: 'member', subjectId: 'member', displayName: '普通成员', role: 2, state: 0, version: 0},
  ]));
  vi.mocked(listMyInvitations).mockResolvedValue(page([invitation]));
});
afterEach(cleanup);

it('limits administrator actions and requires a fresh exact lookup before sending', async () => {
  vi.mocked(findInvitationTarget).mockResolvedValue({subjectId: 'bob-id', displayName: 'bob'});
  vi.mocked(executeMembership).mockResolvedValue(receipt);
  render(<MembershipSession userId="alice"><MembershipPanel target={target} version={6}
                                                            caps={caps}/></MembershipSession>);
  await screen.findByText('负责人账号');
  expect(screen.getAllByRole('button', {name: /移\s*除/})).toHaveLength(1);
  expect(screen.queryByRole('button', {name: '交接负责人'})).toBeNull();
  const field = screen.getByRole('textbox', {name: '受邀人的完整账号'});
  fireEvent.change(field, {target: {value: 'bob'}});
  fireEvent.click(await screen.findByRole('button', {name: '查找账号'}));
  await screen.findByText('受邀账号 bob · 成员');
  fireEvent.change(field, {target: {value: 'bob2'}});
  expect(screen.queryByRole('button', {name: '发送邀请'})).toBeNull();
  fireEvent.change(field, {target: {value: 'bob'}});
  fireEvent.click(await screen.findByRole('button', {name: '查找账号'}));
  fireEvent.click(await screen.findByRole('button', {name: '发送邀请'}));
  await waitFor(() => expect(executeMembership).toHaveBeenCalledWith({
    target, action: 'issue', expectedVersion: 6, payload: {targetSubjectId: 'bob-id', role: 2}
  }, expect.any(String)));
});

it('accepts only after explicit confirmation and restores the original scoped command and key', async () => {
  vi.mocked(executeMembership).mockRejectedValueOnce(new Error('connection lost')).mockResolvedValue(receipt);
  const tree = () => <MembershipSession userId="alice"><MembershipInbox workspace/></MembershipSession>;
  const first = render(tree());
  fireEvent.click(await screen.findByRole('button', {name: '我的邀请'}));
  expect(await screen.findByText('研发空间')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '接受邀请'}));
  expect(executeMembership).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '确认接受'}));
  await waitFor(() => expect(executeMembership).toHaveBeenCalledTimes(1));
  expect(vi.mocked(executeMembership).mock.calls[0]?.[0]).toEqual({
    target, targetLabel: '研发空间', action: 'accept', payload: {invitationId: 'invite', expectedInvitationVersion: 0}
  });
  first.unmount();

  function Status() {
    return useMembershipSession()?.status;
  }

  render(<MembershipSession userId="alice"><Status/></MembershipSession>);
  fireEvent.click(screen.getByRole('button', {name: '确认原成员操作'}));
  await waitFor(() => expect(executeMembership).toHaveBeenCalledTimes(2));
  expect(vi.mocked(executeMembership).mock.calls[1]).toEqual(vi.mocked(executeMembership).mock.calls[0]);
});

it('shows the host, inviter and access boundary before declining without joining', async () => {
  vi.mocked(listMyInvitations).mockResolvedValue(page([{
    ...invitation, tenantName: 'Alice 的协作空间', tenantType: 0,
    hostDisplayName: 'alice', inviterDisplayName: 'lead', sharingBoundary: '私有会话不会向新成员开放。'
  }]));
  vi.mocked(executeMembership).mockResolvedValue(receipt);
  render(<MembershipSession userId="bob"><MembershipInbox workspace initiallyOpen/></MembershipSession>);
  expect(await screen.findByText('托管者：alice')).toBeInTheDocument();
  expect(screen.getByText('邀请人：lead')).toBeInTheDocument();
  expect(screen.getByText('私有会话不会向新成员开放。')).toBeInTheDocument();
  expect(screen.getByRole('button', {name: '收起我的邀请'})).toHaveTextContent('1 条待处理');
  fireEvent.click(screen.getByRole('button', {name: '拒绝邀请'}));
  expect(executeMembership).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '确认拒绝'}));
  await waitFor(() => expect(executeMembership).toHaveBeenCalledWith({
    target, targetLabel: '研发空间', action: 'decline',
    payload: {invitationId: 'invite', expectedInvitationVersion: 0}
  }, expect.any(String)));
});
