import {AiApiError} from '@/services/arte-ai/request';

export const RECOVERY_READ_TIMEOUT_MS = 10_000;
export const RECOVERY_RETRY_DELAYS = [1_000, 3_000] as const;
const aborted = () => new DOMException('Aborted', 'AbortError');

/** 仅用于读取：单次最多十秒，网络／408／429／5xx 最多重试两次；写请求不得使用此入口。 */
export async function recoveryRead<T>(run: (signal: AbortSignal) => Promise<T>, signal: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    if (signal.aborted || document.visibilityState === 'hidden') throw aborted();
    const controller = new AbortController();
    let timeout: ReturnType<typeof setTimeout>;
    let cancel!: () => void;
    const hidden = () => {
      if (document.visibilityState === 'hidden') cancel();
    };
    const deadline = new Promise<never>((_, reject) => {
      cancel = () => {
        controller.abort();
        reject(aborted());
      };
      signal.addEventListener('abort', cancel, {once: true});
      document.addEventListener('visibilitychange', hidden);
      timeout = setTimeout(() => {
        reject(new Error('读取状态超时，请刷新状态。'));
        controller.abort();
      }, RECOVERY_READ_TIMEOUT_MS);
    });
    try {
      return await Promise.race([Promise.resolve().then(() => run(controller.signal)), deadline]);
    } catch (error) {
      if (signal.aborted || (error instanceof Error && error.name === 'AbortError')) throw error;
      const retry = !(error instanceof AiApiError) || [408, 429].includes(error.httpStatus) || error.httpStatus >= 500;
      if (!retry || attempt === RECOVERY_RETRY_DELAYS.length) throw error;
    } finally {
      clearTimeout(timeout!);
      signal.removeEventListener('abort', cancel);
      document.removeEventListener('visibilitychange', hidden);
    }
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        signal.removeEventListener('abort', cancel);
        document.removeEventListener('visibilitychange', hidden);
      };
      const cancel = () => {
        clearTimeout(timer);
        cleanup();
        reject(aborted());
      };
      const hidden = () => {
        if (document.visibilityState === 'hidden') cancel();
      };
      const timer = setTimeout(() => {
        cleanup();
        resolve();
      }, RECOVERY_RETRY_DELAYS[attempt]);
      signal.addEventListener('abort', cancel, {once: true});
      document.addEventListener('visibilitychange', hidden);
      if (signal.aborted || document.visibilityState === 'hidden') cancel();
    });
  }
}
