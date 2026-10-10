import {AiApiError, type SubmitChatRequest} from '@/services/arte-ai';

// Only definitive rejections invalidate discovery. Timeouts / 5xx retain the original retry.
const unavailableCodes = new Set([
  '205001',
  '205002',
  '205023',
  '205025',
  '205026',
]);

export function invalidatesChatConfiguration(error: unknown): boolean {
  return (
    error instanceof AiApiError &&
    error.httpStatus >= 400 &&
    error.httpStatus < 500 &&
    error.httpStatus !== 408 &&
    (error.httpStatus === 401 ||
      error.httpStatus === 403 ||
      unavailableCodes.has(error.code ?? ''))
  );
}

export type ConfigurationRejected = (
  request: SubmitChatRequest,
  error: AiApiError,
) => void;
