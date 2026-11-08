import {afterEach, describe, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import {recoveryRead} from './recoveryRead';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});
describe('bounded recovery reads', () => {
  it.each([408, 429, 500, 503, 0])('retries transient %s only twice at one and three seconds', async status => {
    vi.useFakeTimers();
    const failure = status ? new AiApiError(status, {}) : new TypeError('network');
    const read = vi.fn().mockRejectedValue(failure);
    const settled = recoveryRead(read, new AbortController().signal).catch(e => e);
    await vi.advanceTimersByTimeAsync(0);
    expect(read).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(read).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(read).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3000);
    expect(await settled).toBe(failure);
    await vi.advanceTimersByTimeAsync(60000);
    expect(read).toHaveBeenCalledTimes(3);
  });
  it.each([200, 400, 401, 403, 404, 409])('does not retry contract / permanent HTTP %s', async status => {
    const failure = new AiApiError(status, {});
    const read = vi.fn().mockRejectedValue(failure);
    await expect(recoveryRead(read, new AbortController().signal)).rejects.toBe(failure);
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('times out even a transport ignoring abort and cancels each timed out attempt', async () => {
    vi.useFakeTimers();
    const read = vi.fn((_signal: AbortSignal) => new Promise(() => {
    }));
    const settled = recoveryRead(read, new AbortController().signal).catch(e => e);
    await vi.advanceTimersByTimeAsync(34000);
    expect(await settled).toMatchObject({message: expect.stringContaining('超时')});
    expect(read).toHaveBeenCalledTimes(3);
    expect(read.mock.calls.every(([signal]) => signal.aborted)).toBe(true);
  });
  it('cancels an in flight read and retry delay without accepting late output', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const read = vi.fn().mockRejectedValue(new TypeError('network'));
    const settled = recoveryRead(read, controller.signal).catch(e => e);
    await vi.advanceTimersByTimeAsync(100);
    controller.abort();
    expect(await settled).toMatchObject({name: 'AbortError'});
    await vi.advanceTimersByTimeAsync(30000);
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('does not start reads in a hidden page', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    const read = vi.fn();
    await expect(recoveryRead(read, new AbortController().signal)).rejects.toMatchObject({name: 'AbortError'});
    expect(read).not.toHaveBeenCalled();
  });
});

it('pauses a pending transport immediately on page hide and ignores its late resolution', async () => {
  let finish!: (value: string) => void;
  const read = vi.fn((_signal: AbortSignal) => new Promise<string>(resolve => {
    finish = resolve;
  }));
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const settled = recoveryRead(read, new AbortController().signal).catch(error => error);
  await Promise.resolve();
  visibility.mockReturnValue('hidden');
  document.dispatchEvent(new Event('visibilitychange'));
  expect(await settled).toMatchObject({name: 'AbortError'});
  expect(read.mock.calls[0][0].aborted).toBe(true);
  finish('late');
  expect(read).toHaveBeenCalledTimes(1);
});
