import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolBudgetPicker from './ToolBudgetPicker';
import {availableToolBudgets} from '@/services/arte-ai/toolInvocation';

vi.mock('@/services/arte-ai/toolInvocation', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolInvocation')>()),
  availableToolBudgets: vi.fn(),
}));
vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const price = {
  rate: {type: 'rate', id: 'search', version: 'v1'},
  perCall: {amount: '0.5', currency: 'CNY'},
};
afterEach(cleanup);
beforeEach(() => vi.resetAllMocks());
it('shows the quote and requires explicit selection of an authorized tool account', async () => {
  vi.mocked(availableToolBudgets).mockResolvedValue({
    body: {
      data: {
        price,
        accounts: [
          {budgetRef: 'tool-wallet', currency: 'CNY', available: '4.5'},
        ],
      },
    },
  } as any);
  const change = vi.fn();
  render(
    <ToolBudgetPicker scope={scope} bindingId="search" onChange={change}/>,
  );
  expect(await screen.findByText(/按次报价上界/)).toHaveTextContent('0.5 CNY');
  expect(change).not.toHaveBeenCalled();
  fireEvent.mouseDown(screen.getByRole('combobox'));
  fireEvent.click(await screen.findByText('tool-wallet · 可用 4.5 CNY'));
  expect(change).toHaveBeenCalledWith('tool-wallet', price);
});
it('shows a free tool without an account selector', async () => {
  vi.mocked(availableToolBudgets).mockResolvedValue({
    body: {data: {price: null, accounts: []}},
  } as any);
  render(
    <ToolBudgetPicker scope={scope} bindingId="free" onChange={vi.fn()}/>,
  );
  expect(await screen.findByText(/本工具免费/)).toBeVisible();
  expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
});
it('does not treat unavailable billing information as free', async () => {
  vi.mocked(availableToolBudgets).mockRejectedValue(new Error('unavailable'));
  render(
    <ToolBudgetPicker scope={scope} bindingId="paid" onChange={vi.fn()}/>,
  );
  expect(await screen.findByText(/无法读取工具报价/)).toBeVisible();
  expect(screen.queryByText(/本工具免费/)).not.toBeInTheDocument();
});
it('aborts the previous workspace request and does not paint stale accounts', async () => {
  let resolve!: (v: any) => void;
  vi.mocked(availableToolBudgets).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  vi.mocked(availableToolBudgets).mockResolvedValueOnce({
    body: {data: {price: null, accounts: []}},
  } as any);
  const r = render(
    <ToolBudgetPicker scope={scope} bindingId="one" onChange={vi.fn()}/>,
  );
  await waitFor(() => expect(availableToolBudgets).toHaveBeenCalledTimes(1));
  const signal = vi.mocked(availableToolBudgets).mock.calls[0][2]!.signal;
  r.rerender(
    <ToolBudgetPicker
      scope={{...scope, workspaceId: 'next'}}
      bindingId="one"
      onChange={vi.fn()}
    />,
  );
  expect(signal?.aborted).toBe(true);
  resolve({
    body: {
      data: {
        price,
        accounts: [
          {budgetRef: 'old-wallet', available: '9', currency: 'CNY'},
        ],
      },
    },
  });
  expect(await screen.findByText(/本工具免费/)).toBeVisible();
  expect(screen.queryByText(/old-wallet/)).not.toBeInTheDocument();
});
