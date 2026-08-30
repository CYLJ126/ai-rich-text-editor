package com.arte.core.utils.mytime;

import cn.hutool.core.lang.Pair;
import com.arte.core.enums.MyTimeUnit;
import com.arte.core.i18n.MessageUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author zhangsc
 * @since 2025/2/14 13:59
 */
public class MyDateUtil {

    private MyDateUtil() {
    }

    /**
     * 计算指定日期处于一年中的第几周，从 1 开始
     * 如 2025 年第 1 周表示为 2501
     *
     * @return 第几周
     */
    public static int weekOfYear(LocalDate day) {
        int dayOfYear = day.getDayOfYear();
        // 1 月 1 号是周几
        int firstDay = dayOfWeek(LocalDate.of(day.getYear(), 1, 1)).getKey();
        // 这一年的第一周有几天
        int daysOfFirstWeek = 8 - firstDay;
        if (daysOfFirstWeek - dayOfYear >= 0) {
            // 处于第一周
            return (day.getYear() % 100) * 100 + 1;
        }
        int mod = (dayOfYear - daysOfFirstWeek) % 7;
        // 指定日期是第几周
        int week = (dayOfYear - daysOfFirstWeek) / 7 + (mod == 0 ? 1 : 2);
        // 如 2025 年第 1 周表示为 2501，只支持 20 开头的年份
        return (day.getYear() % 100) * 100 + week;
    }

    /**
     * 列出指定时间段所在的周数
     *
     * @param start 开始日期
     * @param end   结束日期
     * @return 涉及的周数列表
     */
    public static List<Integer> weeksOfYear(LocalDate start, LocalDate end) {
        Set<Integer> weeks = new HashSet<>();
        while (start.isBefore(end)) {
            weeks.add(weekOfYear(start));
            start = start.plusDays(1);
        }
        if (start.isAfter(end)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(weeks);
    }

    /**
     * 获取指定周的第一天
     *
     * @param weekId 指定周
     * @return 指定周的第一天指定周
     */
    public static LocalDate getStartDayOfWeek(int weekId) {
        return listDaysOfWeek(weekId).getFirst();
    }

    /**
     * 获取指定周的最后一天
     *
     * @param weekId 指定周
     * @return 指定周的最后一天指定周
     */
    public static LocalDate getEndDayOfWeek(int weekId) {
        return listDaysOfWeek(weekId).getLast();
    }

    /**
     * 返回指定年份、指定周中每天的日期
     *
     * @param weekId 指定周
     * @return 日期列表
     */
    public static List<LocalDate> listDaysOfWeek(int weekId) {
        int year = weekId / 100;
        int whichWeek = weekId - year * 100;
        year = year + 2000;
        if (whichWeek < 1 || whichWeek > 54) {
            return Collections.emptyList();
        }

        // 1 月 1 号是周几
        LocalDate firstDay = LocalDate.of(year, 1, 1);
        int firstDayIndex = dayOfWeek(firstDay).getKey();
        // 一年第一周的开始是周几
        int firstWeekDayCount = 8 - firstDayIndex;
        List<LocalDate> list = new ArrayList<>();
        if (whichWeek == 1) {
            for (int i = 1; i <= firstWeekDayCount; i++) {
                list.add(LocalDate.of(year, 1, i));
            }
        } else {
            // 获取指定周的开始日期偏移量，1 月 1 号往后偏移 delta 则为 whichWeek 的第一天
            int delta = firstWeekDayCount + (whichWeek - 2) * 7;
            for (int i = 1; i <= 7; i++) {
                LocalDate date = firstDay.plusDays(delta++);
                if (date.getYear() == year) {
                    list.add(date);
                } else {
                    break;
                }
            }
        }
        return list;
    }

    /**
     * 返回指定年份、指定周中每天的日期
     * 如果是一年中的第一周，且不是从周一开始，则加上上一年的最后几天，凑成完整的一周
     *
     * @param weekId 指定周
     * @return 日期列表
     */
    public static List<LocalDate> listDaysOfFullWeek(int weekId) {
        List<LocalDate> days = listDaysOfWeek(weekId);
        if (days.size() == 7) {
            return days;
        }
        if (weekId % 100 == 1) {
            // 获取前一年最后一周的日期
            int preWeekId = MyDateUtil.getPreWeekId(weekId);
            List<LocalDate> preDays = MyDateUtil.listDaysOfWeek(preWeekId);
            days.addAll(0, preDays);
        } else {
            // 获取后一年第一周的日期
            int nextWeekId = MyDateUtil.getNextWeekId(weekId);
            List<LocalDate> nextDays = MyDateUtil.listDaysOfWeek(nextWeekId);
            days.addAll(nextDays);
        }
        return days;
    }

    /**
     * 获取指定周的下一周 ID
     *
     * @param weekId 周 ID
     * @return 下一周 ID
     */
    public static int getNextWeekId(int weekId) {
        List<LocalDate> localDates = listDaysOfWeek(weekId);
        LocalDate firstDayOfNewWeek = localDates.getLast().plusDays(1);
        return weekOfYear(firstDayOfNewWeek);
    }

    /**
     * 获取指定周的上周 ID
     *
     * @param weekId 周 ID
     * @return 上周 ID
     */
    public static int getPreWeekId(int weekId) {
        List<LocalDate> localDates = listDaysOfWeek(weekId);
        LocalDate lastDayOfNewWeek = localDates.getFirst().minusDays(1);
        return weekOfYear(lastDayOfNewWeek);
    }

    /**
     * 计算指定日期是周几，周一为 1，周天为 7
     *
     * @param day 指定日期
     * @return 周几
     */
    public static Pair<Integer, String> dayOfWeek(LocalDate day) {
        switch (day.getDayOfWeek()) {
            case MONDAY -> {
                return Pair.of(1, "周一");
            }
            case TUESDAY -> {
                return Pair.of(2, "周二");
            }
            case WEDNESDAY -> {
                return Pair.of(3, "周三");
            }
            case THURSDAY -> {
                return Pair.of(4, "周四");
            }
            case FRIDAY -> {
                return Pair.of(5, "周五");
            }
            case SATURDAY -> {
                return Pair.of(6, "周六");
            }
            case SUNDAY -> {
                return Pair.of(7, "周日");
            }
            default -> throw new IllegalArgumentException(MessageUtils.get("error.common.dateInvalid"));
        }
    }

    /**
     * 判断指定日期是否包含在日期区间内
     *
     * @param date    指定日期
     * @param start   区间开始日期
     * @param end     区间结束日期
     * @param include 是否包含
     * @return true-在区间内；false-不在区间内；
     */
    public static boolean isIn(LocalDate date, LocalDate start, LocalDate end, boolean include) {
        if (include && (date.equals(start) || date.equals(end))) {
            return true;
        }
        return date.isAfter(start) && date.isBefore(end);
    }

    /**
     * 返回对应周 ID 有几天在指定时间区间内
     *
     * @param weekId 周 ID
     * @param start  区间开始日期
     * @param end    区间结束日期
     * @return 天数
     */
    public static int dayCountOfWeek(int weekId, LocalDate start, LocalDate end) {
        List<LocalDate> localDates = listDaysOfWeek(weekId);
        int count = 0;
        for (LocalDate date : localDates) {
            if (isIn(date, start, end, true)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 返回指定时间的前后偏移量周信息列表
     * 如指定时间为 2025-12-16 13:52，偏移量为3，则返回 [2548,2549,2550,2551,2552,2553,2601,2602] 等周信息
     * 由于偏移量是周数，而存在跨年的情况，所以返回的列表长度并不总是 7
     *
     * @param time   时间
     * @param offset 偏移量
     * @return 周信息列表
     */
    public static List<MyTimeInfoDto> getWeekInfoList(LocalDateTime time, int offset) {
        offset = offset >= 0 ? offset : Math.abs(offset);
        time = Objects.isNull(time) ? LocalDateTime.now() : time;
        LocalDateTime start = time.plusDays((long) offset * 7 * -1);
        LocalDateTime end = time.plusDays((long) offset * 7);
        List<MyTimeInfoDto> myTimeInfoDtoList = new ArrayList<>();
        Set<Integer> weekIdSet = new HashSet<>();
        while (!end.isBefore(start)) {
            int weekId = weekOfYear(start.toLocalDate());
            if (!weekIdSet.contains(weekId)) {
                weekIdSet.add(weekId);
                myTimeInfoDtoList.add(new MyTimeInfoDto(MyTimeUnit.WEEK, weekId, start));
            }
            start = start.plusDays(1);
        }
        return myTimeInfoDtoList;
    }
}
