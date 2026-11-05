import React, {useState} from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import ConfigurationBoundary, {
  type ConfigurationStatus,
  useConfigurationDraft,
  useConfigurationNavigation
} from './ConfigurationBoundary';

vi.mock('@umijs/max', async () => {
  const {createMemoryHistory} = await import('history');
  return {history: createMemoryHistory({initialEntries: ['/config']})};
});
vi.mock('@/features/workspace/useWorkspace', () => ({
  registerWorkspaceGuard: vi.fn(() => () => {
  })
}));
afterEach(cleanup);
beforeEach(() => history.replace('/config'));

function Draft({label, group = 'metadata', status = 'dirty', save, order = 0, discard = true}: {
  label: string; group?: string; status?: ConfigurationStatus; order?: number;
  save?: () => Promise<boolean>; discard?: boolean
}) {
  const [state, setState] = useState(status);
  useConfigurationDraft({
    group, label, status: state, order,
    save: save ? async () => {
      const ok = await save();
      if (ok) setState('clean');
      return ok;
    } : undefined,
    discard: discard ? () => {
      setState('clean');
      return true;
    } : undefined
  });
  return <span>{label}状态：{state}</span>;
}

function Leave({done, groups}: { done: () => void; groups?: string[] }) {
  const navigation = useConfigurationNavigation();
  return <button type="button" onClick={() => navigation.request(done, groups)}>更换配置目标</button>;
}

it('saves ordinary drafts in dependency order and navigates only after all succeed', async () => {
  const calls: string[] = [];
  const done = vi.fn();
  render(<ConfigurationBoundary userId="alice">
    <Draft label="第二项" order={20} save={async () => {
      calls.push('second');
      return true;
    }}/>
    <Draft label="第一项" order={10} save={async () => {
      calls.push('first');
      return true;
    }}/>
    <Leave done={done}/>
  </ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  expect(done).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('保存并继续'));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(calls).toEqual(['first', 'second']);
});

it('stops at a rejected save and retains the target and remaining drafts', async () => {
  const second = vi.fn(async () => true), done = vi.fn();
  render(<ConfigurationBoundary userId="alice">
    <Draft label="第一项" save={async () => false}/><Draft label="第二项" order={20} save={second}/><Leave done={done}/>
  </ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  fireEvent.click(screen.getByText('保存并继续'));
  await screen.findByText('“第一项”尚未处理完成，请核对原页面提示。');
  expect(done).not.toHaveBeenCalled();
  expect(second).not.toHaveBeenCalled();
  expect(screen.getByText('第一项状态：dirty')).toBeInTheDocument();
});

it('discards publishing drafts only on explicit discard and never implicitly publishes', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId="alice"><Draft label="审核发布"/><Leave done={done}/></ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  expect(screen.getByText('保存并继续').closest('button')).toBeDisabled();
  fireEvent.click(screen.getByText('留在本页'));
  expect(done).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('更换配置目标'));
  fireEvent.click(screen.getByText('放弃修改并继续'));
  await waitFor(() => expect(done).toHaveBeenCalledOnce());
});

it.each(['pending', 'submitting', 'blocked'] as const)('never discards or resubmits a %s operation', async status => {
  const save = vi.fn(async () => true), done = vi.fn();
  render(<ConfigurationBoundary userId="alice"><Draft label="空间保存" status={status} save={save}/><Leave done={done}/></ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  expect(screen.getByText('保存并继续').closest('button')).toBeDisabled();
  expect(screen.getByText('放弃修改并继续').closest('button')).toBeDisabled();
  expect(save).not.toHaveBeenCalled();
  expect(done).not.toHaveBeenCalled();
});

it('filters local scope changes while the router still protects platform drafts', async () => {
  const done = vi.fn();
  render(<ConfigurationBoundary userId="alice"><Draft label="平台方案" group="platform"/>
    <Leave done={done} groups={['models']}/>
  </ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  expect(done).toHaveBeenCalledOnce();
  act(() => history.push('/chat'));
  expect(history.location.pathname).toBe('/config');
  fireEvent.click(screen.getByText('放弃修改并继续'));
  await waitFor(() => expect(history.location.pathname).toBe('/chat'));
});

it('coordinates cached page boundaries with a single dialog', async () => {
  render(<ConfigurationBoundary userId="alice">
    <ConfigurationBoundary userId="alice"><Draft label="租户政策"/></ConfigurationBoundary>
    <ConfigurationBoundary userId="alice"><Draft label="空间信息"/></ConfigurationBoundary>
  </ConfigurationBoundary>);
  act(() => history.push('/other'));
  expect(screen.getAllByRole('dialog')).toHaveLength(1);
  fireEvent.click(screen.getByText('放弃修改并继续'));
  await waitFor(() => expect(history.location.pathname).toBe('/other'));
});

it('allows a confirmed continuation to navigate through the same router guard', async () => {
  render(<ConfigurationBoundary userId="alice"><Draft label="模型草稿"/>
    <Leave done={() => history.push('/next')}/>
  </ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  fireEvent.click(screen.getByText('放弃修改并继续'));
  await waitFor(() => expect(history.location.pathname).toBe('/next'));
});

it('abandons a deferred continuation on logout and lets login navigation proceed', async () => {
  let finish!: (ok: boolean) => void;
  const done = vi.fn();
  render(<ConfigurationBoundary userId="alice"><Draft label="保存中" save={() => new Promise(resolve => {
    finish = resolve;
  })}/><Leave done={done}/></ConfigurationBoundary>);
  fireEvent.click(screen.getByText('更换配置目标'));
  fireEvent.click(screen.getByText('保存并继续'));
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: 'alice'})));
  await act(async () => finish(true));
  expect(done).not.toHaveBeenCalled();
  act(() => history.replace('/user/login'));
  expect(history.location.pathname).toBe('/user/login');
});
