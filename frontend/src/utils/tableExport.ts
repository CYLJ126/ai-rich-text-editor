export interface CsvColumn {
  title: string;
  dataIndex: string;
  format?: (value: unknown, row: Record<string, unknown>) => unknown;
}

interface PageResponse<T> {
  success?: boolean;
  records?: T[];
  total?: number;
}

const escapeCsvCell = (value: unknown): string => {
  if (value === null || value === undefined) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
};

export const buildCsv = <T extends Record<string, unknown>>(
  rows: T[],
  columns: CsvColumn[],
): string => {
  const header = columns.map((column) => escapeCsvCell(column.title)).join(',');
  const body = rows.map((row) =>
    columns
      .map((column) => {
        const value = column.format
          ? column.format(row[column.dataIndex], row)
          : row[column.dataIndex];
        return escapeCsvCell(value);
      })
      .join(','),
  );
  return [header, ...body].join('\r\n');
};

export const downloadCsv = <T extends Record<string, unknown>>(
  fileName: string,
  rows: T[],
  columns: CsvColumn[],
): void => {
  const csv = buildCsv(rows, columns);
  const blob = new Blob([`\uFEFF${csv}`], {type: 'text/csv;charset=utf-8'});
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName.endsWith('.csv') ? fileName : `${fileName}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};

export const fetchAllPages = async <T>(
  fetchPage: (params: Record<string, unknown>) => Promise<PageResponse<T>>,
  queryParams: Record<string, unknown> = {},
  pageSize = 500,
): Promise<T[]> => {
  const rows: T[] = [];
  let current = 1;
  let total = Number.POSITIVE_INFINITY;

  while (rows.length < total) {
    const response = await fetchPage({...queryParams, current, size: pageSize});
    if (!response?.success) throw new Error('Failed to load export data');
    const pageRows = response.records ?? [];
    rows.push(...pageRows);
    total = response.total ?? rows.length;
    if (pageRows.length === 0 || rows.length >= total) break;
    current += 1;
  }

  return rows;
};
