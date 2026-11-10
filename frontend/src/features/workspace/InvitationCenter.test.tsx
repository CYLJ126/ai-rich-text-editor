import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {getMyPendingInvitationCounts, listMyInvitations} from '@/services/platform/membership';
import InvitationCenter from './InvitationCenter';

vi.mock('@/services/platform/membership', async original => ({
  ...await original<typeof import('@/services/platform/membership')>(),
  getMyPendingInvitationCounts: vi.fn(), listMyInvitations: vi.fn()
}));
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(getMyPendingInvitationCounts).mockResolvedValue({tenant: 1, workspace: 21});
  vi.mocked(listMyInvitations).mockResolvedValue({records: [], current: 1, size: 20, total: 0});
});
afterEach(cleanup);

it('shows a total beyond a single page and opens both invitation inboxes directly', async () => {
  render(<InvitationCenter userId="bob"/>);
  fireEvent.click(await screen.findByRole('button', {name: '邀请 · 22 条待处理'}));
  expect(await screen.findByRole('dialog', {name: '我的邀请'})).toBeInTheDocument();
  expect(screen.getByText('我的工作空间邀请')).toBeInTheDocument();
  expect(screen.getByText('我的租户邀请')).toBeInTheDocument();
  await waitFor(() => expect(listMyInvitations).toHaveBeenCalledWith(true, 1, expect.anything(), 0));
  expect(listMyInvitations).toHaveBeenCalledWith(false, 1, expect.anything(), 0);
});

it('refreshes for the active account only and clears the notice on logout', async () => {
  render(<InvitationCenter userId="bob"/>);
  await screen.findByRole('button', {name: '邀请 · 22 条待处理'});
  const initial = vi.mocked(getMyPendingInvitationCounts).mock.calls.length;
  act(() => window.dispatchEvent(new CustomEvent('arte-membership-invitations-changed', {detail: 'alice'})));
  expect(getMyPendingInvitationCounts).toHaveBeenCalledTimes(initial);
  vi.mocked(getMyPendingInvitationCounts).mockResolvedValue({tenant: 0, workspace: 1});
  act(() => window.dispatchEvent(new CustomEvent('arte-membership-invitations-changed', {detail: 'bob'})));
  await screen.findByRole('button', {name: '邀请 · 1 条待处理'});
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: 'bob'})));
  expect(screen.queryByRole('button')).toBeNull();
});
