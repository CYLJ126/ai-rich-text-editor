package com.arte.core.utils.mytime;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class MyDateUtilTest {

    @Test
    public void weekOfYear() {
        LocalDate day = LocalDate.of(2025, 1, 1);
        int week = MyDateUtil.weekOfYear(day);
        assert week == 2501;

        day = LocalDate.of(2025, 12, 16);
        week = MyDateUtil.weekOfYear(day);
        assert week == 2551;

        day = LocalDate.of(2025, 12, 31);
        week = MyDateUtil.weekOfYear(day);
        assert week == 2553;

        day = LocalDate.of(2026, 1, 1);
        week = MyDateUtil.weekOfYear(day);
        assert week == 2601;

        day = LocalDate.of(2026, 1, 5);
        week = MyDateUtil.weekOfYear(day);
        assert week == 2602;
    }

    @Test
    public void listDaysOfWeek() {
        List<LocalDate> firstWeek = MyDateUtil.listDaysOfWeek(2501);
        assert verifyDay(firstWeek.get(0), 2025, 1, 1);
        assert verifyDay(firstWeek.get(4), 2025, 1, 5);
        assert firstWeek.size() == 5;

        List<LocalDate> secondWeek = MyDateUtil.listDaysOfWeek(2502);
        assert verifyDay(secondWeek.get(0), 2025, 1, 6);
        assert verifyDay(secondWeek.get(6), 2025, 1, 12);
        assert secondWeek.size() == 7;

        List<LocalDate> sixthWeek = MyDateUtil.listDaysOfWeek(2506);
        assert verifyDay(sixthWeek.get(0), 2025, 2, 3);
        assert verifyDay(sixthWeek.get(6), 2025, 2, 9);
        assert sixthWeek.size() == 7;

        List<LocalDate> lastWeek = MyDateUtil.listDaysOfWeek(2553);
        assert verifyDay(lastWeek.get(0), 2025, 12, 29);
        assert verifyDay(lastWeek.get(2), 2025, 12, 31);
        assert lastWeek.size() == 3;

        List<LocalDate> firstWeek_2026 = MyDateUtil.listDaysOfWeek(2601);
        assert verifyDay(firstWeek_2026.get(0), 2026, 1, 1);
        assert verifyDay(firstWeek_2026.get(3), 2026, 1, 4);
        assert firstWeek_2026.size() == 4;

        List<LocalDate> firstWeek_2024 = MyDateUtil.listDaysOfWeek(2401);
        assert verifyDay(firstWeek_2024.get(0), 2024, 1, 1);
        assert verifyDay(firstWeek_2024.get(6), 2024, 1, 7);
        assert firstWeek_2024.size() == 7;

        List<LocalDate> lastWeek_2024 = MyDateUtil.listDaysOfWeek(2453);
        assert verifyDay(lastWeek_2024.get(0), 2024, 12, 30);
        assert verifyDay(lastWeek_2024.get(1), 2024, 12, 31);
        assert lastWeek_2024.size() == 2;
    }

    private boolean verifyDay(LocalDate date, int year, int month, int day) {
        return date.getYear() == year && date.getMonthValue() == month && date.getDayOfMonth() == day;
    }

    @Test
    public void getWeekInfoList() {
        List<MyTimeInfoDto> weekInfoList = MyDateUtil.getWeekInfoList(LocalDateTime.of(2025, 12, 16, 10, 10, 10), 3);
        assertEquals(weekInfoList.size(), 8);
        assert weekInfoList.get(0).getValue() == 2548;
        assert weekInfoList.get(1).getValue() == 2549;
        assert weekInfoList.get(2).getValue() == 2550;
        assert weekInfoList.get(3).getValue() == 2551;
        assert weekInfoList.get(4).getValue() == 2552;
        assert weekInfoList.get(5).getValue() == 2553;
        assert weekInfoList.get(6).getValue() == 2601;
        assert weekInfoList.get(7).getValue() == 2602;
    }

    @Test
    public void listDaysOfFullWeek() {
        List<LocalDate> week = MyDateUtil.listDaysOfFullWeek(2551);
        assert week.size() == 7;
        assert verifyDay(week.get(0), 2025, 12, 15);
        assert verifyDay(week.get(6), 2025, 12, 21);

        week = MyDateUtil.listDaysOfFullWeek(2553);
        assert week.size() == 7;
        assert verifyDay(week.get(0), 2025, 12, 29);
        assert verifyDay(week.get(6), 2026, 1, 4);

        week = MyDateUtil.listDaysOfFullWeek(2601);
        assert week.size() == 7;
        assert verifyDay(week.get(0), 2025, 12, 29);
        assert verifyDay(week.get(6), 2026, 1, 4);
    }

    @Test
    public void weeksOfYear() {
        LocalDate start = LocalDate.of(2025, 12, 29);
        LocalDate end = LocalDate.of(2026, 1, 4);
        List<Integer> weekIds = MyDateUtil.weeksOfYear(start, end);
        assert weekIds.size() == 2;
        assert weekIds.get(0) == 2553;
        assert weekIds.get(1) == 2601;
    }
}