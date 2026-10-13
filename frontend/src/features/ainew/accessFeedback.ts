import {AiApiError} from '@/services/arte-ai';

/** 通用 403 只能说明授权被拒绝，不能推断当前空间是否已配置模型。 */
export function aiReadAccessIssue(error: unknown): 'login' | 'forbidden' | null {
  if (!(error instanceof AiApiError)) return null;
  if (error.httpStatus === 401) return 'login';
  if (error.httpStatus === 403) return 'forbidden';
  return null;
}
