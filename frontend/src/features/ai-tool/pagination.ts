import type {PageQuery, PageResult} from '@/types/ai.tool.type';

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 200;

export function normalizePageQuery(query: PageQuery = {}): Required<PageQuery> {
  const current = toPositiveInteger(query.current, 1);
  const pageSize = Math.min(
    toPositiveInteger(query.pageSize, DEFAULT_PAGE_SIZE),
    MAX_PAGE_SIZE,
  );
  return {current, pageSize};
}

export function normalizePageResult<T>(
  source?: Partial<PageResult<T>> | null,
): PageResult<T> {
  return {
    code: source?.code ?? '0',
    desc: source?.desc,
    success: source?.success !== false,
    current: toPositiveInteger(source?.current, 1),
    size: toPositiveInteger(source?.size, DEFAULT_PAGE_SIZE),
    total: toNonNegativeInteger(source?.total),
    records: Array.isArray(source?.records) ? source.records : [],
    pages: source?.pages,
  };
}

export function toProTableResult<T>(page: PageResult<T>) {
  return {
    data: page.records,
    total: page.total,
    success: page.success,
  };
}

function toPositiveInteger(value: unknown, fallback: number): number {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : fallback;
}

function toNonNegativeInteger(value: unknown): number {
  const number = Number(value);
  return Number.isInteger(number) && number >= 0 ? number : 0;
}
