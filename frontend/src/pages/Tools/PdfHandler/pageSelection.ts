/** 与后端规则保持一致，供页面输入即时校验。 */
export function parsePageExpression(
  expression: string,
  pageCount: number,
): number[] {
  if (!expression.trim()) return [];

  const pages = new Set<number>();
  const parts = expression
    .replaceAll('，', ',')
    .replaceAll(/\s/g, '')
    .split(',');
  for (const part of parts) {
    if (!part) continue;
    const segments = part.split('-');
    if (segments.length === 1) {
      pages.add(parsePage(segments[0], pageCount));
      continue;
    }
    if (segments.length !== 2) throw new Error(`无效页码范围：${part}`);
    const start = segments[0] ? parsePage(segments[0], pageCount) : 1;
    const end = segments[1] ? parsePage(segments[1], pageCount) : pageCount;
    if (start > end) throw new Error(`起始页不能大于结束页：${part}`);
    for (let page = start; page <= end; page += 1) pages.add(page);
  }
  return [...pages];
}

export function formatPageExpression(pages: number[]): string {
  const sorted = [...new Set(pages)].sort((left, right) => left - right);
  if (!sorted.length) return '';

  const ranges: string[] = [];
  let start = sorted[0];
  let previous = sorted[0];
  for (let index = 1; index <= sorted.length; index += 1) {
    const current = sorted[index];
    if (current === previous + 1) {
      previous = current;
      continue;
    }
    ranges.push(start === previous ? `${start}` : `${start}-${previous}`);
    start = current;
    previous = current;
  }
  return ranges.join(',');
}

function parsePage(value: string, pageCount: number): number {
  if (!/^\d+$/.test(value)) throw new Error(`无效页码：${value}`);
  const page = Number(value);
  if (page < 1 || page > pageCount)
    throw new Error(`页码必须在 1-${pageCount} 之间`);
  return page;
}
