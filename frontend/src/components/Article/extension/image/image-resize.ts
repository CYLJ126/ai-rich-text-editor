export type ImageResizeSide = 'left' | 'right';

export function calculateImageDimensions({
  startWidth,
  startHeight,
  containerWidth,
  deltaX,
  side,
}: {
  startWidth: number;
  startHeight: number;
  containerWidth: number;
  deltaX: number;
  side: ImageResizeSide;
}) {
  if (startWidth <= 0 || startHeight <= 0 || containerWidth <= 0) {
    return {width: startWidth, height: startHeight};
  }

  const aspectRatio = startWidth / startHeight;
  const minimumWidth = Math.min(
    containerWidth,
    Math.max(150, 150 * aspectRatio),
  );
  const requestedWidth = startWidth + (side === 'right' ? deltaX : -deltaX);
  const width = Math.round(
    Math.min(containerWidth, Math.max(minimumWidth, requestedWidth)),
  );

  return {width, height: Math.round(width / aspectRatio)};
}
