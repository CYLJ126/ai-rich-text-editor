import {act, cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import ChatMessageList from './ChatMessageList';

const makeTurn = (
  sequence: number,
  output = `回答${sequence}`,
): ChatTurnResult => ({
  turn: {
    turnId: `turn-${sequence}`,
    conversationId: 'conversation',
    sequence,
    kind: 'MESSAGE',
    status: 'ACCEPTED',
    regeneratesTurnId: null,
    input: [{role: 'USER', parts: [{text: `问题${sequence}`}]}],
    idempotencyKey: {key: `key-${sequence}`},
    rejectionError: null,
    createdAt: '2026-10-03T00:00:00Z',
  },
  execution: {
    executionId: `execution-${sequence}`,
    status: 'SUCCEEDED',
    result: {output: [{text: output}]},
    error: null,
  },
});

beforeEach(() => {
  localStorage.setItem('umi_locale', 'zh-CN');
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('chat reading experience', () => {
  it('smoothly displays a multi-line delta, follows the visible text and offers skipping after completion', () => {
    let height = 1000;
    let now = 0;
    let nextId = 0;
    const frames = new Map<number, FrameRequestCallback>();
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames.set(++nextId, callback);
      return nextId;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));
    const play = () => act(() => {
      now += 17;
      const callbacks = [...frames.values()];
      frames.clear();
      for (const callback of callbacks) callback(now);
    });
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(() => height);
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(300);
    const running = makeTurn(1);
    const execution = running.execution;
    if (!execution) throw new Error('Expected an execution in the fixture');
    running.execution = {...execution, status: 'RUNNING', result: null, partialText: ''};
    const {container, rerender} = render(<ChatMessageList turns={[running]}/>);
    const text = '一二三四五六七八九十\n'.repeat(20);
    const live: ChatTurnResult = {
      ...running,
      execution: {...execution, status: 'RUNNING', result: null, partialText: text}
    };
    rerender(<ChatMessageList turns={[live]}/>);
    const answer = container.querySelector('article [aria-busy]');
    expect(answer).toHaveTextContent('');
    height = 1100;
    play();
    expect(answer?.querySelector('p')?.textContent).toBe('一二三');
    const log = screen.getByRole('log');
    expect(log.scrollTop).toBe(1100);
    log.scrollTop = 200;
    fireEvent.scroll(log);
    height = 1200;
    play();
    expect(answer?.querySelector('p')?.textContent).toBe('一二三四五六');
    expect(log.scrollTop).toBe(200);
    expect(screen.getByRole('button', {name: '查看最新消息／状态'})).toBeInTheDocument();
    rerender(<ChatMessageList turns={[makeTurn(1, text)]}/>);
    expect(answer?.querySelector('p')?.textContent).toBe('一二三四五六');
    expect(answer).toHaveAttribute('aria-busy', 'true');
    fireEvent.click(screen.getByRole('button', {name: '显示完整回答'}));
    expect(answer?.querySelector('p')?.textContent).toBe(text.trimEnd());
    expect(answer).toHaveAttribute('aria-busy', 'false');
    expect(log.scrollTop).toBe(200);
    rerender(<ChatMessageList turns={[makeTurn(2)]}/>);
    play();
    expect(screen.getByText('回答2')).toBeInTheDocument();
    expect(screen.queryByText('一二三四五六')).not.toBeInTheDocument();
  });
  it('labels incomplete output and only offers answer copying after authoritative success', () => {
    const turn = makeTurn(1);
    turn.execution = {
      ...turn.execution!,
      status: 'OUTCOME_UNKNOWN',
      result: null,
      partialText: '**部分回答**',
      partialSequence: 2
    };
    const {rerender} = render(<ChatMessageList turns={[turn]}/>);
    expect(screen.getByText('部分回答').tagName).toBe('STRONG');
    expect(screen.getByText('正在生成或未完成的部分回答')).toBeInTheDocument();
    expect(screen.queryByRole('button', {name: '复制回答'})).not.toBeInTheDocument();
    expect(screen.getByText(/模型调用结果未知/)).toBeInTheDocument();
    rerender(<ChatMessageList turns={[makeTurn(1, '**完整回答**')]}/>);
    expect(screen.getByText('完整回答')).toBeInTheDocument();
    expect(screen.queryByText('正在生成或未完成的部分回答')).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: '复制回答'})).toBeInTheDocument();
  });
  it('renders Markdown and copies the original question, answer and code without executing HTML or loading images', async () => {
    const text =
      '## 标题\n\n**重点**与`inline`\n\n- 条目\n\n```js\nconst n = 1;\n```\n\n[文档](https://example.com/docs)\n\n[危险](javascript:alert%281%29)\n\n![图片说明](https://example.com/tracker.png)\n\n<script>alert(1)</script>\n\n<iframe src="https://example.com"></iframe>';
    const write = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue();
    const {container} = render(
      <ChatMessageList turns={[makeTurn(1, text)]}/>,
    );
    expect(screen.getByRole('heading', {name: '标题'})).toBeInTheDocument();
    expect(screen.getByText('重点').tagName).toBe('STRONG');
    expect(screen.getByRole('listitem')).toHaveTextContent('条目');
    expect(container.querySelector('pre code')).toHaveTextContent(
      'const n = 1;',
    );
    const link = screen.getByRole('link', {name: '文档'});
    expect(link).toHaveAttribute('href', 'https://example.com/docs');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(
      screen.queryByRole('link', {name: '危险'}),
    ).not.toBeInTheDocument();
    expect(container.querySelector('img, script, iframe')).toBeNull();
    expect(screen.getByText('图片说明')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name: '复制问题'}));
    await waitFor(() => expect(write).toHaveBeenCalledWith('问题1'));
    fireEvent.click(screen.getByRole('button', {name: '复制回答'}));
    await waitFor(() => expect(write).toHaveBeenCalledWith(text));
    fireEvent.click(screen.getByRole('button', {name: '复制代码'}));
    await waitFor(() => expect(write).toHaveBeenCalledWith('const n = 1;\n'));
    await waitFor(() => expect(screen.getAllByText('已复制')).toHaveLength(3));
  });

  it('keeps the answer selectable and reports clipboard denial without false success', async () => {
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(
      new Error('denied'),
    );
    render(<ChatMessageList turns={[makeTurn(1)]}/>);
    fireEvent.click(screen.getByRole('button', {name: '复制回答'}));
    await screen.findByText('复制失败，请选中文本手动复制。');
    expect(screen.getByText('回答1')).toBeInTheDocument();
    expect(screen.queryByText('已复制')).not.toBeInTheDocument();
  });

  it('preserves the viewport while prepending history and does not pull a reader away when new results arrive', () => {
    let height = 1000;
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(
      () => height,
    );
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(300);
    const {rerender} = render(<ChatMessageList turns={[makeTurn(2)]}/>);
    const log = screen.getByRole('log');
    expect(log.scrollTop).toBe(1000);
    log.scrollTop = 200;
    fireEvent.scroll(log);
    height = 1400;
    rerender(<ChatMessageList turns={[makeTurn(1), makeTurn(2)]}/>);
    expect(log.scrollTop).toBe(600);
    expect(
      screen.queryByRole('button', {name: '查看最新消息／状态'}),
    ).not.toBeInTheDocument();
    height = 1800;
    rerender(
      <ChatMessageList turns={[makeTurn(1), makeTurn(2), makeTurn(3)]}/>,
    );
    expect(log.scrollTop).toBe(600);
    fireEvent.click(screen.getByRole('button', {name: '查看最新消息／状态'}));
    expect(log.scrollTop).toBe(1800);
    expect(
      screen.queryByRole('button', {name: '查看最新消息／状态'}),
    ).not.toBeInTheDocument();
    height = 2000;
    rerender(
      <ChatMessageList
        turns={[makeTurn(1), makeTurn(2), makeTurn(3, '新的回答')]}
      />,
    );
    expect(log.scrollTop).toBe(2000);
    expect(
      screen.queryByRole('button', {name: '查看最新消息／状态'}),
    ).not.toBeInTheDocument();
  });
});
