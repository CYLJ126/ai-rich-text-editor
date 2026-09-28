import type {ToolApprovalRecord, ToolApprovalStatus,} from '@/types/ai.tool.type';

export function isApprovalExpired(
  approval: Pick<ToolApprovalRecord, 'status' | 'expiresAt'>,
  now = Date.now(),
): boolean {
  if (approval.status === 'expired') return true;
  const expiresAt = Date.parse(approval.expiresAt);
  return (
    approval.status === 'pending' &&
    Number.isFinite(expiresAt) &&
    expiresAt <= now
  );
}

export function effectiveApprovalStatus(
  approval: Pick<ToolApprovalRecord, 'status' | 'expiresAt'>,
  now = Date.now(),
): ToolApprovalStatus {
  return isApprovalExpired(approval, now) ? 'expired' : approval.status;
}

export function canDecideApproval(
  approval: Pick<ToolApprovalRecord, 'status' | 'expiresAt'>,
  now = Date.now(),
): boolean {
  return effectiveApprovalStatus(approval, now) === 'pending';
}

export const GUARDRAIL_DESCRIPTIONS: Record<string, string> = {
  'unsafe-input': '识别脚本、JNDI、路径穿越等明显危险输入并拒绝执行。',
  'parameter-normalization': '根据受信策略转换和规范化输入参数。',
  'risk-approval': '高风险、破坏性或有副作用的工具必须进入人工审批。',
  'post-execution-integrity': '执行后验证成功响应结构，阻止异常结果继续传播。',
  'sensitive-output': '对结构化输出中的敏感字段进行递归脱敏。',
};
