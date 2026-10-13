import {type AiRequestOptions, currentAiCredential, postResult, requireIdempotencyKey, shareAiRead} from './request';
import type {
  ControlReceipt,
  InvocationEventsRequest,
  InvocationEventsResponse,
  InvocationQuery,
  InvocationResultResponse,
  InvocationStatusResponse,
} from './types';

export function getInvocationStatus(
  data: InvocationQuery,
  options?: AiRequestOptions,
) {
  return postResult<InvocationStatusResponse>(
    'invocation/getInvocationStatus',
    data,
    options,
  );
}

export function getInvocationResult(
  data: InvocationQuery,
  options?: AiRequestOptions,
) {
  return postResult<InvocationResultResponse>(
    'invocation/getInvocationResult',
    data,
    options,
  );
}

/** 单页耐久事件重放，不是 SSE；调用方负责保存游标和去重。 */
export function invocationEvent(
  data: InvocationEventsRequest,
  options?: AiRequestOptions,
) {
  return postResult<InvocationEventsResponse>(
    'invocation/invocationEvent',
    data,
    options,
  );
}

/** 耐久停止请求；HTTP 202 不表示执行或费用已确认，继续观察权威状态。 */
export function cancelInvocation(data: InvocationQuery, idempotencyKey: string, options?: AiRequestOptions) {
  return postResult<ControlReceipt>('invocation/cancelInvocation', data, options, requireIdempotencyKey(idempotencyKey));
}

export function getInvocationSnapshots(data: {
  scope: import('./types').AiScope;
  invocationIds: string[];
  knownResultVersions?: Record<string, number>
}, options?: AiRequestOptions) {
  return postResult<import('./types').InvocationSnapshotResponse[]>('invocation/getInvocationSnapshots', data, options);
}

type Snapshot = import('./types').InvocationSnapshotResponse;
type SnapshotQuery = InvocationQuery & { knownResultVersion?: number };
type SnapshotRead = {
  query: SnapshotQuery;
  signal: AbortSignal;
  resolve: (value: Snapshot) => void;
  reject: (error: unknown) => void
};
const batches = new Map<string, SnapshotRead[]>();

/** 同一轮调度的单条读取组成最多 20 条一批；历史、SSE 与停止恢复共用该入口。 */
export function getInvocationSnapshot(query: SnapshotQuery, options?: AiRequestOptions): Promise<Snapshot> {
  return shareAiRead(['invocation-snapshot', query], options?.signal, signal => new Promise((resolve, reject) => {
    const key = JSON.stringify([currentAiCredential(), query.scope.tenantId, query.scope.workspaceId]);
    const item = {query, signal, resolve, reject};
    const queued = batches.get(key);
    if (queued) {
      queued.push(item);
      return;
    }
    const pending = [item];
    batches.set(key, pending);
    queueMicrotask(() => {
      batches.delete(key);
      const active = pending.filter(read => {
        if (!read.signal.aborted) return true;
        read.reject(new DOMException('Aborted', 'AbortError'));
        return false;
      });
      for (let offset = 0; offset < active.length; offset += 20) {
        const group = active.slice(offset, offset + 20);
        const controller = new AbortController();
        const cancel = () => {
          if (group.every(read => read.signal.aborted)) controller.abort();
        };
        group.forEach(read => read.signal.addEventListener('abort', cancel, {once: true}));
        cancel();
        const ids = [...new Set(group.map(read => read.query.invocationId))];
        // 同一调用只要有一个使用者缺少结果，就必须返回字节，不能替另一使用者省略。
        const knownResultVersions = Object.fromEntries(ids.flatMap(id => {
          const consumers = group.filter(read => read.query.invocationId === id);
          const version = consumers[0].query.knownResultVersion;
          return version !== undefined && consumers.every(read => read.query.knownResultVersion === version) ? [[id, version]] : [];
        }));
        void getInvocationSnapshots({
          scope: query.scope,
          invocationIds: ids,
          knownResultVersions
        }, {signal: controller.signal})
          .then(({body}) => {
            const values = new Map(body.data.map(value => [value.invocationId, value]));
            group.forEach(read => {
              const value = values.get(read.query.invocationId);
              if (value) read.resolve(value);
              else read.reject(new Error('Invocation batch response is incomplete'));
            });
          }).catch(error => group.forEach(read => read.reject(error)))
          .finally(() => group.forEach(read => read.signal.removeEventListener('abort', cancel)));
      }
    });
  }));
}
