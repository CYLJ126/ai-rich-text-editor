import {afterEach, expect, it, vi} from 'vitest';
import {beginChatLatency, markChatDomCommit, markChatLatency} from './latency';

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState({}, '', '/');
});

it('只在显式诊断模式记录阶段时间，连续片段不覆盖首段时间', () => {
  window.history.replaceState({}, '', '/?aiLatency=1');
  let now = 100;
  vi.spyOn(performance, 'now').mockImplementation(() => now);
  const logged = vi.spyOn(console, 'info').mockImplementation(() => {
  });
  const frames: FrameRequestCallback[] = [];
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => frames.push(callback));
  const accept = beginChatLatency();
  now = 120;
  accept('measured');
  now = 130;
  markChatLatency('measured', 'firstTextReceived');
  now = 140;
  markChatLatency('measured', 'firstTextReceived');
  markChatDomCommit('measured');
  now = 150;
  frames.shift()!(150);
  frames.shift()!(150);
  const report = JSON.parse(logged.mock.calls[0][1]);
  expect(report).toMatchObject({accepted: 20, firstTextReceived: 30, domCommitted: 40, paintOpportunity: 50});
  expect(logged).toHaveBeenCalledOnce();
  window.dispatchEvent(new Event('arte-account-session-cleared'));
  markChatLatency('measured', 'paintOpportunity');
  expect(logged).toHaveBeenCalledOnce();
});

it('普通模式不打印诊断，未受理的历史回复不安排额外帧', () => {
  const logged = vi.spyOn(console, 'info').mockImplementation(() => {
  });
  const frame = vi.spyOn(window, 'requestAnimationFrame');
  markChatDomCommit('history-only');
  expect(frame).not.toHaveBeenCalled();
  beginChatLatency()('normal');
  markChatLatency('normal', 'paintOpportunity');
  expect(logged).not.toHaveBeenCalled();
});
