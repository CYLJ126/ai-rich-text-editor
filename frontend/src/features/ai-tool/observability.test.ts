import {describe, expect, it} from 'vitest';
import {formatDuration, formatSuccessRate, traceDurationMs,} from './observability';

describe('AI tool observability helpers', () => {
  it('calculates trace duration independent of event order', () => {
    expect(
      traceDurationMs([
        {occurredAt: '2026-09-28T10:00:02.500Z'},
        {occurredAt: '2026-09-28T10:00:00.000Z'},
      ]),
    ).toBe(2500);
    expect(traceDurationMs([{occurredAt: 'invalid'}])).toBeUndefined();
  });

  it('formats bounded metrics', () => {
    expect(formatDuration(999)).toBe('999 ms');
    expect(formatDuration(2500)).toBe('2.50 s');
    expect(formatSuccessRate(1.2)).toBe('100.0%');
    expect(formatSuccessRate(Number.NaN)).toBe('0.0%');
  });
});
