import dayjs, {type Dayjs} from 'dayjs';

/**
 * 月内业务周：自然周（周一至周日）与自然月的交集。
 * 数字 ID 使用 YYMMWW，例如 260905 表示 2026-09-W05。
 */
export interface WeekInfo {
  year: number;
  month: number;
  week: number;
  value: number;
  time: Dayjs;
  periodStart: Dayjs;
  periodEnd: Dayjs;
  label: string;
}

const mondayBasedDay = (date: Dayjs): number =>
  date.day() === 0 ? 7 : date.day();

const pad = (value: number): string => String(value).padStart(2, '0');

const resolveWeekDays = (
  year: number,
  month: number,
  week: number,
): Dayjs[] => {
  if (
    year < 2000 ||
    year > 2099 ||
    month < 1 ||
    month > 12 ||
    week < 1 ||
    week > 6
  ) {
    return [];
  }
  const monthStart = dayjs(`${year}-${pad(month)}-01`);
  const firstSegmentDays = 8 - mondayBasedDay(monthStart);
  const segmentStart =
    week === 1
      ? monthStart
      : monthStart.add(firstSegmentDays + (week - 2) * 7, 'day');
  if (segmentStart.month() + 1 !== month) return [];

  const candidateEnd = segmentStart.add(
    week === 1 ? firstSegmentDays - 1 : 6,
    'day',
  );
  const segmentEnd = candidateEnd.isAfter(monthStart.endOf('month'), 'day')
    ? monthStart.endOf('month')
    : candidateEnd;
  const days: Dayjs[] = [];
  for (
    let cursor = segmentStart;
    !cursor.isAfter(segmentEnd, 'day');
    cursor = cursor.add(1, 'day')
  ) {
    days.push(cursor);
  }
  return days;
};

export const getWeekDays = (weekId: number): Dayjs[] => {
  const year = 2000 + Math.floor(weekId / 10_000);
  const month = Math.floor(weekId / 100) % 100;
  const week = weekId % 100;
  return resolveWeekDays(year, month, week);
};

export const formatWeekLabel = (weekId: number): string => {
  const days = getWeekDays(weekId);
  if (days.length === 0) return String(weekId);
  const start = days[0];
  const end = days[days.length - 1];
  return `${start.format('YYYY-MM')}-W${pad(weekId % 100)} · ${start.format('MM/DD')}—${end.format('MM/DD')}`;
};

export async function getWeekInfoList(time: any, offset: any) {
  // TODO: implement
  return Promise.resolve([]);
}
