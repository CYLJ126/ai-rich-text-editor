import React, {useState} from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {useAliveController} from 'react-activation';
import TabsLayout from './index';
import ConfigurationBoundary, {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';

vi.mock('@umijs/max', async () => {
  const {createMemoryHistory} = await import('history');
  const {useSyncExternalStore} = await import('react');
  const history = createMemoryHistory({initialEntries: ['/Environment/Models']});
  return {
    history,
    useLocation: () => useSyncExternalStore(listener => history.listen(listener), () => history.location),
    useIntl: () => ({formatMessage: ({defaultMessage}: { defaultMessage: string }) => defaultMessage})
  };
});
vi.mock('react-activation', () => ({useAliveController: vi.fn()}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  registerWorkspaceGuard: vi.fn(() => () => {
  })
}));
const drop = vi.fn(async () => true);
beforeEach(() => {
  vi.clearAllMocks();
  history.replace('/Environment/Models');
  vi.mocked(useAliveController).mockReturnValue({drop, getCachingNodes: () => []} as never);
});
afterEach(cleanup);

function Draft() {
  const [dirty, setDirty] = useState(true);
  useConfigurationDraft({
    group: 'models', label: '模型草稿', status: dirty ? 'dirty' : 'clean', discard: () => {
      setDirty(false);
      return true;
    }
  });
  return null;
}

it('retains the cached page and active route until closing is confirmed', async () => {
  render(<ConfigurationBoundary userId="alice"><TabsLayout/><Draft/></ConfigurationBoundary>);
  fireEvent.click(await screen.findByRole('button', {name: '关闭 Models'}));
  expect(drop).not.toHaveBeenCalled();
  expect(history.location.pathname).toBe('/Environment/Models');
  fireEvent.click(screen.getByRole('button', {name: '留在本页'}));
  expect(drop).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '关闭 Models'}));
  fireEvent.click(screen.getByRole('button', {name: '放弃修改并继续'}));
  await waitFor(() => expect(drop).toHaveBeenCalledWith('/Environment/Models'));
  expect(history.location.pathname).toBe('/HomePage');
});

it('does not mark another top tab active while routing is blocked', async () => {
  render(<ConfigurationBoundary userId="alice"><TabsLayout/><Draft/></ConfigurationBoundary>);
  await screen.findByRole('tab', {name: /Models/});
  fireEvent.click(screen.getByRole('tab', {name: 'HomePage'}));
  expect(history.location.pathname).toBe('/Environment/Models');
  expect(screen.getByRole('tab', {name: /Models/})).toHaveAttribute('aria-selected', 'true');
  fireEvent.click(screen.getByRole('button', {name: '留在本页'}));
  act(() => history.replace('/Environment/Models'));
  expect(drop).not.toHaveBeenCalled();
});
