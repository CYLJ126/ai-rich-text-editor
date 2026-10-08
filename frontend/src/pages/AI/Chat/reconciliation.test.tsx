import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import {
  AiApiError,
  confirmReconciliation,
  type PendingReconciliation,
  pendingReconciliations,
} from '@/services/arte-ai';
import ReconciliationPanel from './ReconciliationPanel';
import {chatConfigSchema, DEFAULT_CHAT_CONFIG} from './config';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  confirmReconciliation: vi.fn(),
  pendingReconciliations: vi.fn(),
}));
const config = chatConfigSchema.parse({
  ...DEFAULT_CHAT_CONFIG,
  tenantId: 'tenant',
  workspaceId: 'workspace',
  capabilityId: 'text',
  capabilityVersion: 'v1',
  bindingId: 'binding',
  bindingVersion: 'v1',
  budgetRef: 'budget',
});
const item: PendingReconciliation = {
  invocationId: 'stopped',
  conversationId: 'conversation',
  state: 'UNKNOWN',
  invocationVersion: 4,
  reservationId: 'reservation',
  reservationVersion: 1,
  budgetRef: 'budget',
  reserved: {amount: '1.25', currency: 'CNY'},
  remoteRequestId: 'remote-123',
  acceptedAt: '2026-10-08T01:00:00Z',
};
const t = (key: string) =>
  messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
const reply = <T, >(data: T) => ({
  httpStatus: 200,
  body: {success: true as const, code: '200', desc: 'OK', data},
});
const onConfirmed = vi.fn(async () => {
});
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(pendingReconciliations).mockResolvedValue(
    reply({
      page: {current: 1, size: 10, total: 1, records: [item]},
      canReconcile: true,
    }),
  );
});
afterEach(cleanup);

async function open() {
  render(
    <ReconciliationPanel config={config} t={t} onConfirmed={onConfirmed}/>,
  );
  expect(pendingReconciliations).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole('button', {name: t('reconciliation.open')}),
  );
  await screen.findByRole('button', {name: t('reconciliation.review')});
  fireEvent.click(
    screen.getByRole('button', {name: t('reconciliation.review')}),
  );
}

function fill(amount = '0.123456789012345678') {
  fireEvent.change(
    screen.getByRole('textbox', {name: t('reconciliation.amount')}),
    {target: {value: amount}},
  );
  fireEvent.change(
    screen.getByRole('textbox', {name: t('reconciliation.evidence')}),
    {target: {value: 'bill-123'}},
  );
  fireEvent.change(
    screen.getByRole('textbox', {name: t('reconciliation.note')}),
    {target: {value: '执行结束，账单核实'}},
  );
  fireEvent.click(
    screen.getByRole('checkbox', {name: t('reconciliation.ended')}),
  );
}

describe('费用核对', () => {
  it('按权限提供只读列表，普通用户不能确认费用', async () => {
    vi.mocked(pendingReconciliations).mockResolvedValue(
      reply({
        page: {current: 1, size: 10, total: 1, records: [item]},
        canReconcile: false,
      }),
    );
    render(
      <ReconciliationPanel config={config} t={t} onConfirmed={onConfirmed}/>,
    );
    fireEvent.click(
      screen.getByRole('button', {name: t('reconciliation.open')}),
    );
    await screen.findByText(t('reconciliation.permission'));
    expect(
      screen.queryByRole('button', {name: t('reconciliation.review')}),
    ).not.toBeInTheDocument();
    expect(confirmReconciliation).not.toHaveBeenCalled();
  });
  it('不预填估算费用，需要真实金额、凭据、说明和执行结束确认', async () => {
    await open();
    expect(
      screen.getByRole('textbox', {name: t('reconciliation.amount')}),
    ).toHaveValue('');
    expect(
      screen.getByRole('button', {name: t('reconciliation.confirm')}),
    ).toBeDisabled();
    fill('-1');
    expect(
      screen.getByRole('button', {name: t('reconciliation.confirm')}),
    ).toBeDisabled();
    fireEvent.change(
      screen.getByRole('textbox', {name: t('reconciliation.amount')}),
      {target: {value: '0'}},
    );
    expect(
      screen.getByRole('button', {name: t('reconciliation.confirm')}),
    ).toBeEnabled();
    expect(confirmReconciliation).not.toHaveBeenCalled();
  });
  it('网络不确定后沿用原表单和幂等键，高精度金额以字符串提交', async () => {
    vi.mocked(confirmReconciliation).mockRejectedValueOnce(
      new TypeError('offline'),
    );
    await open();
    fill();
    fireEvent.click(
      screen.getByRole('button', {name: t('reconciliation.confirm')}),
    );
    await screen.findByRole('button', {name: t('reconciliation.retry')});
    expect(
      screen.getByRole('textbox', {name: t('reconciliation.amount')}),
    ).toBeDisabled();
    const original = vi.mocked(confirmReconciliation).mock.calls[0];
    expect(original[0]).toMatchObject({
      actualCharge: '0.123456789012345678',
      invocationVersion: 4,
      reservationVersion: 1,
      evidenceRef: 'bill-123',
      note: '执行结束，账单核实',
      executionEnded: true,
    });
    vi.mocked(confirmReconciliation).mockResolvedValue(
      reply({
        key: original[1],
        invocationId: 'stopped',
        reservationId: 'reservation',
        budgetRef: 'budget',
        charge: {amount: original[0].actualCharge, currency: 'CNY'},
        evidenceRef: 'bill-123',
        note: '执行结束，账单核实',
        reviewer: {subjectId: 'admin', subjectName: 'admin', kind: 'USER'},
        traceId: 'trace',
        state: 'CANCELLED',
        invocationVersion: 5,
        reservationVersion: 2,
        confirmedAt: '2026-10-08T01:00:00Z',
      }),
    );
    vi.mocked(pendingReconciliations).mockResolvedValue(
      reply({
        page: {current: 1, size: 10, total: 0, records: []},
        canReconcile: true,
      }),
    );
    fireEvent.click(
      screen.getByRole('button', {name: t('reconciliation.retry')}),
    );
    await screen.findByText(t('reconciliation.done'));
    expect(vi.mocked(confirmReconciliation).mock.calls[1].slice(0, 2)).toEqual(
      original.slice(0, 2),
    );
    expect(onConfirmed).toHaveBeenCalledWith('stopped');
  });
  it('版本冲突重新读取列表并要求重新选择，不能沿用失效预留版本扣费', async () => {
    vi.mocked(confirmReconciliation).mockRejectedValue(
      new AiApiError(409, {success: false, code: '205029', desc: '版本冲突'}),
    );
    await open();
    fill();
    fireEvent.click(
      screen.getByRole('button', {name: t('reconciliation.confirm')}),
    );
    await waitFor(() =>
      expect(pendingReconciliations).toHaveBeenCalledTimes(2),
    );
    expect(
      screen.queryByRole('textbox', {name: t('reconciliation.amount')}),
    ).not.toBeInTheDocument();
    expect(onConfirmed).not.toHaveBeenCalled();
  });
});
