import dayjs, {Dayjs} from 'dayjs';
import weekOfYear from 'dayjs/plugin/weekOfYear';
import isoWeek from 'dayjs/plugin/isoWeek';
import weekday from 'dayjs/plugin/weekday';

// 启用需要的插件
dayjs.extend(weekOfYear);
dayjs.extend(isoWeek);
dayjs.extend(weekday);

export interface WeekInfo {
  year: number; // 2025 年
  week: number; // 第 45 周
  value: number; // 如 2025 年第 45 周，则为 2545
  time: Dayjs; // 指定周内的某个时间点
}

/**
 * 计算给定日期所在周的信息
 * 与后端 {com.cylj126.core.utils.mytime.MyDateUtil#weekOfYear} 一致
 * @param targetDate
 */
export const getWeekInfo = (targetDate: Dayjs): WeekInfo => {
  const day = targetDate.toDate();
  const year = day.getFullYear();
  const jan1 = new Date(year, 0, 1); // 获取当年1月1日
  // 计算指定日期是当年的第几天（1月1日为第1天）
  const startOfYear = new Date(year, 0, 1);
  const timeDiff = day - startOfYear;
  const dayOfYear = Math.floor(timeDiff / (1000 * 60 * 60 * 24)) + 1;
  // 计算1月1日是周几（Java中：周一=1，周日=7；JS中：getDay()返回0=周日，1=周一...6=周六）
  const jan1Day = jan1.getDay(); // JS的周几（0-6）
  const firstDay = jan1Day === 0 ? 7 : jan1Day; // 转换为Java的周几格式（1-7）
  // 计算当年第一周的天数（1月1日所在周的天数）
  const daysOfFirstWeek = 8 - firstDay;

  let week, value;
  // 如果指定日期在第一周内
  if (daysOfFirstWeek - dayOfYear >= 0) {
    value = (year % 100) * 100 + 1;
    week = 1;
  } else {
    // 计算第一周之后的剩余天数
    const remainingDays = dayOfYear - daysOfFirstWeek;
    const mod = remainingDays % 7;
    // 计算周数：剩余天数/7，根据余数调整（整除加1，否则加2）
    week = Math.floor(remainingDays / 7);
    week += mod === 0 ? 1 : 2;
    // 组装周ID（年份后两位*100 + 周数）
    value = (year % 100) * 100 + week;
  }
  return {year, week, value, time: targetDate};
};
