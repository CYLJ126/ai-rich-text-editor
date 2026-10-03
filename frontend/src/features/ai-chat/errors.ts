import {AiNewApiError} from '@/services/ai-new/request';
import {i18nText} from '@/utils/i18n';
import type {ExecutionError} from '@/types/ai-new/conversation';

export function chatErrorText(error: unknown): string {
  if (!(error instanceof AiNewApiError))
    return i18nText('app.aiNew.error.general');
  const code = error.facts?.code;
  if (isAccessError(error) || code === 'arte.common.unauthorized') return i18nText('app.aiNew.error.access');
  if (error.timedOut) return i18nText('app.aiNew.error.transportTimeout');
  if (error.facts?.resultCertainty === 'UNKNOWN' || error.facts?.sideEffectStatus === 'UNKNOWN')
    return i18nText('app.aiNew.error.uncertain');
  if (error.facts?.failureStage === 'budget') return i18nText(code === 'arte.common.rate_limited' ? 'app.aiNew.error.budgetLimit' : 'app.aiNew.error.budgetPolicy');
  if (code === 'arte.common.version_conflict')
    return i18nText('app.aiNew.error.version');
  if (code === 'arte.common.busy') return i18nText('app.aiNew.error.busy');
  if (code === 'arte.common.idempotency_conflict') return i18nText('app.aiNew.error.idempotency');
  if (code === 'arte.common.rate_limited' || error.status === 429) return i18nText('app.aiNew.error.rateLimit');
  if (code === 'arte.common.policy_unavailable') return i18nText('app.aiNew.error.policy');
  if (code === 'arte.common.deadline_exceeded') return i18nText('app.aiNew.error.deadline');
  if (code === 'arte.common.unsupported') return i18nText('app.aiNew.error.unsupported');
  if (code === 'arte.common.interrupted') return i18nText('app.aiNew.error.interrupted');
  if (error.status === 401 || error.status === 403)
    return i18nText('app.aiNew.error.access');
  if (error.status === 404) return i18nText('app.aiNew.error.missing');
  if (error.status === 400) return i18nText('app.aiNew.error.input');
  if (error.status === 502) return i18nText('app.aiNew.error.contract');
  if (error.facts?.resultCertainty === 'CONFIRMED' && error.facts.sideEffectStatus === 'NONE')
    return i18nText('app.aiNew.error.rejected');
  if (error.status === 0 && !error.facts) return i18nText('app.aiNew.error.network');
  return i18nText('app.aiNew.error.uncertain');
}

export function executionErrorText(error: ExecutionError) {
  return chatErrorText(new AiNewApiError(0, error));
}

export function isAccessError(error: unknown): boolean {
  return (
    error instanceof AiNewApiError &&
    (error.status === 401 || error.status === 403)
  );
}
