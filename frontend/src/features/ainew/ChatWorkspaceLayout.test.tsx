import React, {useEffect} from 'react';
import {act, cleanup, fireEvent, render, screen} from '@testing-library/react';
import {afterEach, expect, it, vi} from 'vitest';
import {
  type ChatLayout,
  chatLayoutReducer as reduce,
  initialChatLayout,
  layoutMode,
  useChatLayout
} from './useChatLayout';
import ChatWorkspaceLayout from './ChatWorkspaceLayout';

vi.mock('@/components/DynamicIcon', () => ({MyDynamicIcon: () => <span/>}));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('uses the available container thresholds for 350px sides and a 480px chat', () => {
  expect([349, 350, 375, 780, 829, 830, 1080, 1179, 1180].map(layoutMode))
    .toEqual(['single', 'single', 'single', 'single', 'single', 'two', 'two', 'two', 'three']);
  expect(initialChatLayout(1200)).toMatchObject({left: 350, right: 350, leftOpen: true, rightOpen: true});
  expect(initialChatLayout(900)).toMatchObject({leftOpen: true, rightOpen: false});
  expect(initialChatLayout(375)).toMatchObject({region: 'chat', leftOpen: false, rightOpen: false});
});
it('preserves the chat minimum and the 50% side maximum during drag and shrink', () => {
  let value = reduce(initialChatLayout(1600), {type: 'resize', sizes: [800, 450, 350]});
  expect(value.left).toBe(770);
  value = reduce(value, {type: 'resize', sizes: [1000, 200, 400]});
  expect(value.left).toBeLessThanOrEqual(800);
  expect(1600 - value.left - value.right).toBeGreaterThanOrEqual(480);
  value = reduce(value, {type: 'measure', width: 1180});
  expect(value.left).toBe(350);
  expect(value.right).toBe(350);
});
it('restores the last width on collapse and starts a new page with defaults', () => {
  let value = reduce(initialChatLayout(1600), {type: 'resize', sizes: [420, 820, 360]});
  value = reduce(value, {type: 'resize', sizes: [0, 1240, 360]});
  expect(value.left).toBe(420);
  expect(value.leftOpen).toBe(false);
  value = reduce(value, {type: 'open', region: 'list'});
  expect(value.left).toBe(420);
  expect(value.leftOpen).toBe(true);
  expect(initialChatLayout(1600).left).toBe(350);
});
it('opens only one side in two-region mode and never collapses the chat there', () => {
  let value = reduce(initialChatLayout(900), {type: 'open', region: 'settings'});
  expect(value).toMatchObject({leftOpen: false, rightOpen: true});
  value = reduce(value, {type: 'resize', sizes: [0, 550, 350]});
  expect(value).toMatchObject({leftOpen: false, rightOpen: true});
  value = reduce(value, {type: 'open', region: 'list'});
  expect(value).toMatchObject({leftOpen: true, rightOpen: false});
});
it('adapts existing widths when crossing modes without using browser preferences', () => {
  let value = reduce(initialChatLayout(1600), {type: 'open', region: 'settings'});
  value = reduce(value, {type: 'measure', width: 900});
  expect(value).toMatchObject({mode: 'two', leftOpen: false, rightOpen: true});
  value = reduce(value, {type: 'measure', width: 375});
  expect(value).toMatchObject({mode: 'single', region: 'chat'});
  value = reduce(value, {type: 'open', region: 'settings'});
  value = reduce(value, {type: 'measure', width: 900});
  expect(value.rightOpen).toBe(true);
  expect(reduce(value, {type: 'measure', width: 1600})).toMatchObject({mode: 'three', leftOpen: true, rightOpen: true});
});
it('ignores invalid measurements and drag input', () => {
  const value = initialChatLayout();
  expect(reduce(value, {type: 'measure', width: 0})).toBe(value);
  expect(reduce(value, {type: 'resize', sizes: [NaN, 1, 2]})).toBe(value);
  expect(reduce(value, {type: 'measure', width: value.width})).toBe(value);
});

it('crosses breakpoints even when the measured width changes by less than half a pixel', () => {
  let value = reduce(initialChatLayout(829.75), {type: 'measure', width: 830});
  expect(value).toMatchObject({mode: 'two', leftOpen: true, rightOpen: false});
  expect(value.width - value.left).toBe(480);
  value = reduce(value, {type: 'measure', width: 829.75});
  expect(value).toMatchObject({mode: 'single', region: 'chat'});
  value = reduce(initialChatLayout(1179.75), {type: 'measure', width: 1180});
  expect(value).toMatchObject({mode: 'three', leftOpen: true, rightOpen: true});
  expect(value.width - value.left - value.right).toBe(480);
  expect(reduce(value, {type: 'measure', width: 1179.75}).mode).toBe('two');
});

it.each([830, 831, 1080, 1179, 1180, 1181, 1366, 1920])(
  'preserves the chat and side minimums after dragging and reopening at %ipx', width => {
    let value = initialChatLayout(1920);
    value = reduce(value, {type: 'resize', sizes: [900, 480, 540]});
    value = reduce(value, {type: 'measure', width});
    for (const region of ['list', 'settings'] as const) {
      value = reduce(value, {type: 'open', region});
      value = reduce(value, {type: 'resize', sizes: [width, 0, width]});
      const sides = [value.leftOpen ? value.left : 0, value.rightOpen ? value.right : 0];
      expect(width - sides[0] - sides[1]).toBeGreaterThanOrEqual(480);
      for (const side of sides.filter(Boolean)) {
        expect(side).toBeGreaterThanOrEqual(350);
        expect(side).toBeLessThanOrEqual(width / 2);
      }
      value = reduce(value, {type: 'close', region});
    }
  }
);

it('measures the container, keeps all three trees mounted, and transfers focus when hiding a region', async () => {
  let layout: ChatLayout;
  let mounted = 0, unmounted = 0;
  const observers: { target?: Element; callback: ResizeObserverCallback }[] = [];
  vi.stubGlobal('ResizeObserver', class {
    record: typeof observers[number];

    constructor(callback: ResizeObserverCallback) {
      this.record = {callback};
      observers.push(this.record);
    }

    observe(target: Element) {
      this.record.target = target;
    }

    unobserve() {
    }

    disconnect() {
    }
  });

  function Chat() {
    useEffect(() => {
      mounted++;
      return () => {
        unmounted++;
      };
    }, []);
    return <textarea aria-label="保留的消息草稿" defaultValue="原草稿"/>;
  }

  function Harness() {
    layout = useChatLayout();
    return <ChatWorkspaceLayout layout={layout} list={<input aria-label="列表草稿" defaultValue="名称稿"/>}
                                settings={<input aria-label="参数草稿"
                                                 defaultValue="512"/>}><Chat/></ChatWorkspaceLayout>;
  }

  const view = render(<Harness/>);
  expect(screen.queryByRole('navigation', {name: '聊天区域切换'})).toBeNull();
  const root = view.container.querySelector('.ainew-layout')!;
  const observer = observers.find(item => item.target === root)!;
  await act(async () => observer.callback([{
    target: root,
    contentRect: {width: 349}
  } as ResizeObserverEntry], {} as ResizeObserver));
  expect(screen.getByRole('status')).toHaveTextContent('至少需要 350px');
  await act(async () => observer.callback([{
    target: root,
    contentRect: {width: 375}
  } as ResizeObserverEntry], {} as ResizeObserver));
  expect(screen.queryByRole('status')).toBeNull();
  expect(layout!.sizes).toEqual([0, 375, 0]);
  expect(screen.getByRole('button', {name: '展开设置侧栏'})).toBeVisible();
  const chat = screen.getByLabelText('保留的消息草稿');
  fireEvent.change(chat, {target: {value: '继续保留'}});
  fireEvent.click(screen.getByRole('button', {name: '展开会话列表'}));
  expect(layout!.sizes).toEqual([375, 0, 0]);
  expect(screen.queryByRole('button', {name: '展开会话列表'})).toBeNull();
  expect(view.container.querySelector('[data-region="list"]')).toHaveFocus();
  expect(screen.getByRole('button', {name: '返回聊天'}).closest('[data-region]')).toHaveAttribute('data-region', 'list');
  const name = screen.getByLabelText('列表草稿');
  name.focus();
  await act(async () => layout!.close('list'));
  expect(view.container.querySelector('[data-region="chat"]')).toHaveFocus();
  expect(screen.getByLabelText('保留的消息草稿')).toBe(chat);
  expect(chat).toHaveValue('继续保留');
  fireEvent.click(screen.getByRole('button', {name: '展开设置侧栏'}));
  fireEvent.change(screen.getByLabelText('参数草稿'), {target: {value: '2048'}});
  fireEvent.click(screen.getByRole('button', {name: '返回聊天'}));
  expect(view.container.querySelector('[data-region="chat"]')).toHaveFocus();
  await act(async () => observer.callback([{
    target: root,
    contentRect: {width: 1400}
  } as ResizeObserverEntry], {} as ResizeObserver));
  expect(screen.getByLabelText('参数草稿')).toHaveValue('2048');
  expect(mounted).toBe(1);
  expect(unmounted).toBe(0);
  expect(screen.queryByRole('button', {name: '展开会话列表'})).toBeNull();
  expect(screen.queryByRole('button', {name: '展开设置侧栏'})).toBeNull();
  await act(async () => layout!.resize([420, 620, 360]));
  const parameters = screen.getByLabelText('参数草稿');
  parameters.focus();
  await act(async () => {
    layout!.close('list');
    layout!.close('settings');
  });
  expect(screen.getByRole('button', {name: '展开设置侧栏'})).toHaveFocus();
  fireEvent.click(screen.getByRole('button', {name: '展开会话列表'}));
  expect(layout!.sizes).toEqual([420, 980, 0]);
  expect(view.container.querySelector('[data-region="list"]')).toHaveFocus();
  fireEvent.click(screen.getByRole('button', {name: '展开设置侧栏'}));
  expect(layout!.sizes).toEqual([420, 620, 360]);
  expect(chat).toHaveValue('继续保留');
  expect(parameters).toHaveValue('2048');
  expect(unmounted).toBe(0);
  expect(view.container.querySelector('.ant-drawer')).toBeNull();
});


it('switches to the newly expanded Splitter side when only one side fits', () => {
  let value = reduce(initialChatLayout(900), {type: 'open', region: 'settings'});
  value = reduce(value, {type: 'resize', sizes: [350, 200, 350]});
  expect(value).toMatchObject({leftOpen: true, rightOpen: false, lastSide: 'list'});
  value = reduce(value, {type: 'resize', sizes: [350, 200, 350]});
  expect(value).toMatchObject({leftOpen: false, rightOpen: true, lastSide: 'settings'});
});
