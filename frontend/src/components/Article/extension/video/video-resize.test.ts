import {describe, expect, it} from 'vitest';
import {calculateVideoWidthPercent} from './video-resize';

describe('calculateVideoWidthPercent', () => {
  it('resizes from either side relative to the document width', () => {
    expect(
      calculateVideoWidthPercent({
        startWidth: 800,
        containerWidth: 1000,
        deltaX: -200,
        side: 'right',
      }),
    ).toBe(60);
    expect(
      calculateVideoWidthPercent({
        startWidth: 800,
        containerWidth: 1000,
        deltaX: 200,
        side: 'left',
      }),
    ).toBe(60);
  });

  it('keeps the result within the supported percentage range', () => {
    expect(
      calculateVideoWidthPercent({
        startWidth: 800,
        containerWidth: 1000,
        deltaX: 1000,
        side: 'right',
      }),
    ).toBe(100);
    expect(
      calculateVideoWidthPercent({
        startWidth: 800,
        containerWidth: 1000,
        deltaX: -1000,
        side: 'right',
      }),
    ).toBe(1);
  });
});
