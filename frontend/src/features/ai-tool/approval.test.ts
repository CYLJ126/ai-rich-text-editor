import {describe, expect, it} from 'vitest';
import {canDecideApproval, effectiveApprovalStatus, isApprovalExpired,} from './approval';

const pending = {
  status: 'pending' as const,
  expiresAt: '2026-09-28T10:00:00Z',
};

describe('AI tool approval helpers', () => {
  it('treats elapsed pending approvals as expired', () => {
    expect(isApprovalExpired(pending, Date.parse('2026-09-28T10:00:01Z'))).toBe(
      true,
    );
    expect(
      effectiveApprovalStatus(pending, Date.parse('2026-09-28T10:00:01Z')),
    ).toBe('expired');
  });

  it('allows decisions only while a request is pending and unexpired', () => {
    expect(canDecideApproval(pending, Date.parse('2026-09-28T09:59:59Z'))).toBe(
      true,
    );
    expect(canDecideApproval({...pending, status: 'approved'})).toBe(false);
  });
});
