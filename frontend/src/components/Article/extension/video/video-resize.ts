export type VideoResizeSide = 'left' | 'right';

export function calculateVideoWidthPercent({
  startWidth,
  containerWidth,
  deltaX,
  side,
}: {
  startWidth: number;
  containerWidth: number;
  deltaX: number;
  side: VideoResizeSide;
}) {
  if (containerWidth <= 0) return 100;

  const nextWidth = startWidth + (side === 'right' ? deltaX : -deltaX);
  return Math.min(100, Math.max(1, Math.round((nextWidth / containerWidth) * 100)));
}
