export const colorfulColors = [
  'var(--color-chinese-red)',
  'var(--color-chinese-orange)',
  'var(--color-chinese-yellow)',
  'var(--color-chinese-green)',
  'var(--color-chinese-blue)',
  'var(--color-chinese-indigo)',
  'var(--color-chinese-purple)',
];

/**
 * 设置 7 种颜色：['#ce2416', '#f78922', '#f6c114', '#64bd89', '#59aec6', '#2484b6', '#7f3b83']
 * 根据传入下标和偏移量，返回颜色
 */
export function getColorByIndex(index: number, offset: number = 0) {
  return colorfulColors[(index + offset) % colorfulColors.length];
}
