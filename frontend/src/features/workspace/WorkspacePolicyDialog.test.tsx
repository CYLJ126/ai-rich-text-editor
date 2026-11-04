import React from 'react';
import {cleanup, fireEvent, render, screen} from '@testing-library/react';
import {afterEach, expect, it, vi} from 'vitest';
import WorkspacePolicyDialog from './WorkspacePolicyDialog';

vi.mock('./PolicyEditor', () => ({
  default: ({scope, kind, onUnsafeChange}: {
    scope: { tenantId: string; workspaceId?: string }; kind: string; onUnsafeChange: (unsafe: boolean) => void
  }) => <div>政策 {kind} {scope.tenantId} {scope.workspaceId}
    <button onClick={() => onUnsafeChange(true)}>修改{kind}</button>
    <button onClick={() => onUnsafeChange(false)}>放弃{kind}</button>
  </div>
}));
afterEach(cleanup);
it('uses the selected workspace scope and keeps a dirty policy dialog open', async () => {
  const close = vi.fn(), unsafe = vi.fn();
  render(<WorkspacePolicyDialog userId="alice" target={{
    scope: {tenantId: 'tenant-B', workspaceId: 'B'},
    name: '空间B',
    state: 0,
    currentRole: 0,
    version: 1
  }}
                                onClose={close} onUnsafeChange={unsafe}/>);
  expect(await screen.findByText('政策 workspace tenant-B B')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '修改workspace'}));
  expect(unsafe).toHaveBeenLastCalledWith(true);
  expect(screen.queryByRole('button', {name: 'Close'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '放弃workspace'}));
  fireEvent.click(screen.getByRole('button', {name: 'Close'}));
  expect(close).toHaveBeenCalledTimes(1);
});
