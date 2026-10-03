import {AiNewApiError} from '@/services/ai-new/request';
import {i18nText} from '@/utils/i18n';

export function chatErrorText(error: unknown): string {
  if (!(error instanceof AiNewApiError))
    return i18nText('app.aiNew.error.general');
  const code = error.facts?.code;
  if (code === 'arte.common.version_conflict')
    return i18nText('app.aiNew.error.version');
  if (code === 'arte.common.busy') return i18nText('app.aiNew.error.busy');
  if (error.status === 401 || error.status === 403)
    return i18nText('app.aiNew.error.access');
  if (error.status === 404) return i18nText('app.aiNew.error.missing');
  if (error.status === 400) return i18nText('app.aiNew.error.input');
  if (error.status === 502) return i18nText('app.aiNew.error.contract');
  return i18nText('app.aiNew.error.uncertain');
}

export function isAccessError(error: unknown): boolean {
  return (
    error instanceof AiNewApiError &&
    (error.status === 401 || error.status === 403)
  );
}
