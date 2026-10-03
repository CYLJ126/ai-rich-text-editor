import type {ChatTurnResult} from '@/types/ai-new/chat';

export function isTurnPending(item: ChatTurnResult) {
  return (
    item.turn.status === 'PREPARING' ||
    item.turn.status === 'READY' ||
    item.execution?.status === 'ACCEPTED' ||
    item.execution?.status === 'RUNNING'
  );
}

export function canRegenerateTurn(item: ChatTurnResult) {
  return (
    item.turn.status === 'ACCEPTED' &&
    item.execution !== null &&
    !isTurnPending(item) &&
    item.execution.status !== 'OUTCOME_UNKNOWN' &&
    item.execution.error?.resultCertainty !== 'UNKNOWN'
  );
}
