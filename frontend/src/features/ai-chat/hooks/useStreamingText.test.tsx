import {act, cleanup, renderHook} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {useStreamingText} from './useStreamingText';

let frames: Map<number, FrameRequestCallback>;
let now: number;
let nextId: number;
const play = (count = 1) => {
  for (let i = 0; i < count; i++) act(() => {
    now += 17;
    const batch = [...frames.values()];
    frames.clear();
    for (const callback of batch) callback(now);
  });
};
const mount = (text = '', streaming = true, succeeded = false) => renderHook(
  props => useStreamingText(props.text, props.streaming, props.succeeded),
  {initialProps: {text, streaming, succeeded}},
);

beforeEach(() => {
  frames = new Map();
  now = 0;
  nextId = 0;
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++nextId, callback);
    return nextId;
  });
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('smooth live answer display', () => {
  it('reveals a large chunk in one to three graphemes per frame, including emoji and combining marks', () => {
    const {result, rerender} = mount();
    const text = `中👨‍👩‍👧‍👦e\u0301${'逐字显示'.repeat(40)}`;
    rerender({text, streaming: true, succeeded: false});
    expect(result.current.text).toBe('');
    play();
    expect(result.current.text).toBe('中👨‍👩‍👧‍👦e\u0301');
    let previous = result.current.text;
    const segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});
    for (let i = 0; frames.size && i < 200; i++) {
      play();
      const added = result.current.text.slice(previous.length);
      expect([...segmenter.segment(added)].length).toBeGreaterThanOrEqual(1);
      expect([...segmenter.segment(added)].length).toBeLessThanOrEqual(3);
      previous = result.current.text;
    }
    expect(result.current.text).toBe(text);
    expect(result.current.isTyping).toBe(false);
  });

  it('keeps draining queued text after success instead of showing the final answer all at once', () => {
    const {result, rerender} = mount();
    rerender({text: '你好世界'.repeat(30), streaming: true, succeeded: false});
    play();
    const partial = result.current.text;
    const final = `${'你好世界'.repeat(30)}完成`;
    rerender({text: final, streaming: false, succeeded: true});
    expect(result.current.text).toBe(partial);
    expect(result.current.isTyping).toBe(true);
    play(150);
    expect(result.current.text).toBe(final);
    expect(frames.size).toBe(0);
  });

  it('does not postpone an existing animation frame when more deltas arrive', () => {
    const {result, rerender} = mount();
    rerender({text: '你好', streaming: true, succeeded: false});
    const frame = [...frames.keys()][0];
    rerender({text: '你好世界', streaming: true, succeeded: false});
    rerender({text: '你好世界继续', streaming: true, succeeded: false});
    expect([...frames.keys()]).toEqual([frame]);
    play();
    expect(result.current.text).toBe('你');
    play(10);
    expect(result.current.text).toBe('你好世界继续');
  });

  it('renders history and restored partial snapshots immediately, animating only new live text', () => {
    const historical = mount('完整历史回答', false, true);
    expect(historical.result.current.text).toBe('完整历史回答');
    const restored = mount('已有部分回答');
    expect(restored.result.current.text).toBe('已有部分回答');
    restored.rerender({text: '已有部分回答新增内容', streaming: true, succeeded: false});
    play();
    expect(restored.result.current.text).toBe('已有部分回答新');
  });

  it('flushes received partial text on failure or cancellation and cancels the animation', () => {
    const {result, rerender} = mount();
    const partial = '已经收到的部分'.repeat(20);
    rerender({text: partial, streaming: true, succeeded: false});
    play();
    rerender({text: partial, streaming: false, succeeded: false});
    expect(result.current.text).toBe(partial);
    expect(result.current.isTyping).toBe(false);
    expect(frames.size).toBe(0);
  });

  it('shows corrected authoritative results immediately without appending incompatible text', () => {
    const {result, rerender} = mount();
    rerender({text: '旧的部分回答'.repeat(20), streaming: true, succeeded: false});
    play();
    rerender({text: '修正后的完整回答', streaming: false, succeeded: true});
    expect(result.current.text).toBe('修正后的完整回答');
    expect(frames.size).toBe(0);
  });

  it('allows skipping the remaining display animation and cleans up on unmount', () => {
    const {result, rerender, unmount} = mount();
    rerender({text: '很长的完整回答'.repeat(40), streaming: true, succeeded: false});
    play();
    act(() => result.current.finish());
    expect(result.current.text).toBe('很长的完整回答'.repeat(40));
    expect(frames.size).toBe(0);
    rerender({text: `${'很长的完整回答'.repeat(40)}后续`, streaming: true, succeeded: false});
    expect(frames.size).toBe(1);
    unmount();
    expect(frames.size).toBe(0);
  });

  it('respects reduced motion and flushes a queue when the tab becomes hidden', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    } as unknown as MediaQueryList);
    const reduced = mount();
    reduced.rerender({text: '减少动态效果', streaming: true, succeeded: false});
    expect(reduced.result.current.text).toBe('减少动态效果');
    expect(frames.size).toBe(0);
    vi.restoreAllMocks();
    const hidden = mount();
    hidden.rerender({text: '后台页签中的回答'.repeat(40), streaming: true, succeeded: false});
    play();
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(hidden.result.current.text).toBe('后台页签中的回答'.repeat(40));
    expect(frames.size).toBe(0);
  });
});
