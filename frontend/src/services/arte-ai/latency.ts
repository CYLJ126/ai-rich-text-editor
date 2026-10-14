type Stage =
  'accepted'
  | 'sseRequest'
  | 'sseConnected'
  | 'firstTextReceived'
  | 'firstUsableText'
  | 'domCommitted'
  | 'paintOpportunity';
type Sample = { started: number; invocationId?: string; stages: Partial<Record<Stage, number>>; reported?: boolean };
const samples = new Map<string, Sample>();

/** 仅浏览器本地诊断，不记录正文、Token 或身份；保留最近 32 次受理的时间点。 */
export function beginChatLatency() {
  const sample: Sample = {started: performance.now(), stages: {}};
  return (invocationId: string) => {
    sample.invocationId = invocationId;
    samples.delete(invocationId);
    samples.set(invocationId, sample);
    while (samples.size > 32) samples.delete(samples.keys().next().value!);
    markChatLatency(invocationId, 'accepted');
  };
}

export function markChatLatency(invocationId: string, stage: Stage) {
  const sample = samples.get(invocationId);
  if (!sample || sample.stages[stage] !== undefined) return;
  sample.stages[stage] = Math.round((performance.now() - sample.started) * 100) / 100;
  if (stage === 'paintOpportunity' && !sample.reported) {
    sample.reported = true;
    // 显式诊断开关；帧回调只表示一次绘制机会，不等于像素级首字证据。
    if (new URLSearchParams(location.search).get('aiLatency') === '1')
      console.info('AI_CHAT_LATENCY', JSON.stringify({
        invocationId, ...sample.stages,
        visibility: document.visibilityState
      }));
  }
}

/** DOM 提交后跨过一次帧边界；取消仅清理当前组件的诊断回调，不影响生成。 */
export function markChatDomCommit(invocationId: string) {
  if (!samples.has(invocationId) || samples.get(invocationId)?.reported) return () => {
  };
  markChatLatency(invocationId, 'domCommitted');
  let second: number | undefined;
  const first = requestAnimationFrame(() => {
    second = requestAnimationFrame(() => markChatLatency(invocationId, 'paintOpportunity'));
  });
  return () => {
    cancelAnimationFrame(first);
    if (second !== undefined) cancelAnimationFrame(second);
  };
}

if (typeof window !== 'undefined') window.addEventListener('arte-account-session-cleared', () => samples.clear());
