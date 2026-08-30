package com.arte.core.utils;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import com.arte.core.enums.MyTimeUnit;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 从文本中提取时间
 *
 * @author zhangsc
 * @since 2025/9/27 16:06
 */
@Slf4j
public class TimeExtractUtil {

    private TimeExtractUtil() {
    }

    /**
     * 提取多行文本行中的时间并加总，以指定单位返回
     *
     * @param raw  文本
     * @param unit 时间单位
     * @return 总时间
     */
    public static double extractLinesTime(String raw, MyTimeUnit unit) {
        raw = StrUtil.trim(raw);
        if (StrUtil.isEmpty(raw)) {
            return 0.0;
        }
        String[] lines = raw.split("\r?\n");
        double totalTime = 0.0;
        for (String line : lines) {
            Pair<String, Double> pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
            totalTime += pair.getValue();
        }
        return MyTimeUnit.convert(MyTimeUnit.MINUTE, unit, totalTime);
    }

    /**
     * 提取当前行文本中的时间
     * 可指定时间单位：y：年；q：季；m：月；d：日；h：小时；min：分钟；s：秒；ms：毫秒；us：微秒；ns：纳秒；
     * 如果文本中有多个匹配的时间，取最后一个时间，如“工作2h吃饭30min”，单位要求分钟时，则返回 <工作2h吃饭，0.5>
     * <p>
     * 匹配可能的时间格式：例如 "-1h" "1h" "40min" "2h40min" "1y2m" "1y2q3m"；匹配数字后跟y或q或m或d或h或min，但不匹配横杠（负号）
     * "工作-1h" 或 "工作1h"：unit 为小时时，返回 <工作，1>；unit 为分钟时，返回 <工作，60>；
     * "工作-2h30min" 或"工作2h30min" 或 "工作2.5h"：unit 为小时时，返回 <工作，2.5>；unit 为分钟时，返回 <工作，150>；
     * "工作-1y2q3m"：unit 为年时，返回<工作，1.75>；unit 为季时，返回<工作，7>；unit 为月时，返回<工作，21>；unit 为小时时，返回 <工作，8760>；
     *
     * @param line 文本行
     * @param unit 时间单位
     * @return 分割后的<文本 ， 时间>
     */
    public static Pair<String, Double> extractLineTime(String line, MyTimeUnit unit) {
        if (line == null || line.trim().isEmpty()) {
            return new Pair<>(line, 0.0);
        }
        log.debug("输入: '{}'", line);
        // 找到所有单独的时间匹配
        List<TimeMatch> allMatches = findAllTimeMatches(line);
        log.debug("所有时间匹配: {}", allMatches);
        if (allMatches.isEmpty()) {
            return new Pair<>(line, 0.0);
        }
        // 将所有匹配按位置分组为连续的时间段
        List<List<TimeMatch>> timeGroups = groupConsecutiveMatches(allMatches, line);
        log.debug("时间分组: ");
        for (int i = 0; i < timeGroups.size(); i++) {
            List<TimeMatch> group = timeGroups.get(i);
            log.debug("  组{}: {}", i, group.stream()
                    .map(m -> m.originalText)
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        // 取最后一个时间组
        List<TimeMatch> lastGroup = timeGroups.get(timeGroups.size() - 1);
        int startIndex = lastGroup.get(0).startIndex;
        int endIndex = lastGroup.get(lastGroup.size() - 1).endIndex;
        TimeGroup lastTimeGroup = new TimeGroup(lastGroup, startIndex, endIndex);
        log.debug("最后一个时间组: {}", lastTimeGroup);
        // 计算总时间并转换为目标单位
        double totalTime = calculateTotalTime(lastTimeGroup.matches, unit);
        log.debug("计算得到的时间: {} {}", totalTime, unit.getValue());
        // 提取内容（移除最后一个时间组合）
        String content = extractContent(line, lastTimeGroup);
        log.debug("提取的内容: '{}'", content);
        return new Pair<>(content, totalTime);
    }

    /**
     * 查找所有时间匹配
     *
     * @param line 文本行
     * @return 时间匹配列表
     */
    private static List<TimeMatch> findAllTimeMatches(String line) {
        Matcher matcher = MyTimeUnit.TIME_PATTERN.matcher(line);
        List<TimeMatch> allMatches = new ArrayList<>();
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            String numberStr = matcher.group(1);
            String unitStr = matcher.group(2);
            double value = Double.parseDouble(numberStr);
            MyTimeUnit timeUnit = parseTimeUnit(unitStr);

            if (timeUnit != null) {
                allMatches.add(new TimeMatch(matcher.start(), matcher.end(), value, timeUnit, fullMatch));
            } else {
                log.debug("无法解析时间单位: {}", unitStr);
            }
        }
        return allMatches;
    }

    /**
     * 将时间匹配按连续性分组
     *
     * @param allMatches 所有时间匹配
     * @param line       文本行
     * @return 时间分组列表
     */
    private static List<List<TimeMatch>> groupConsecutiveMatches(List<TimeMatch> allMatches, String line) {
        List<List<TimeMatch>> groups = new ArrayList<>();

        if (allMatches.isEmpty()) {
            return groups;
        }

        List<TimeMatch> currentGroup = new ArrayList<>();
        currentGroup.add(allMatches.get(0));

        for (int i = 1; i < allMatches.size(); i++) {
            TimeMatch previous = allMatches.get(i - 1);
            TimeMatch current = allMatches.get(i);

            // 检查两个时间匹配是否连续
            boolean consecutive = isConsecutive(previous, current, line);
            log.debug("检查连续性: '{}' 和 '{}' -> {}", previous.originalText, current.originalText, consecutive);

            if (consecutive) {
                currentGroup.add(current);
            } else {
                // 完成当前组，开始新组
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentGroup.add(current);
            }
        }
        // 添加最后一个组
        groups.add(currentGroup);
        return groups;
    }

    /**
     * 判断两个时间匹配是否连续
     *
     * @param previous 前一个时间匹配
     * @param current  当前时间匹配
     * @param line     文本行
     * @return 是否连续
     */
    private static boolean isConsecutive(TimeMatch previous, TimeMatch current, String line) {
        // 获取两个匹配之间的字符串
        String between = line.substring(previous.endIndex, current.startIndex);
        log.debug("两个匹配之间的内容: '{}'", between);
        // 情况1: 直接相连 (如 "1y2q3m" 中的 "1y" 和 "2q")
        if (between.isEmpty()) {
            return true;
        }
        // 情况2: 只有负号 (如 "工作2h-30min" 中的 "2h" 和 "30min")
        if ("-".equals(between)) {
            return true;
        }
        // 情况3: 只有空格或空格加负号 (如 "工作2h -30min" 或 "工作2h - 30min")
        return between.trim().isEmpty() || "-".equals(between.trim());
    }

    /**
     * 计算时间组合的总时间
     * @param timeMatches 时间匹配列表
     * @param targetUnit 目标单位
     * @return 总时间
     */
    private static double calculateTotalTime(List<TimeMatch> timeMatches, MyTimeUnit targetUnit) {
        double totalTime = 0.0;
        for (TimeMatch timeMatch : timeMatches) {
            // 使用简单的转换逻辑
            double convertedTime = MyTimeUnit.convert(timeMatch.unit, targetUnit, Math.abs(timeMatch.value));
            log.debug("  转换时间: {}{} -> {}{}", timeMatch.value, timeMatch.unit.getValue(), convertedTime, targetUnit.getValue());
            totalTime += convertedTime;
        }
        return totalTime;
    }

    /**
     * 提取内容（移除时间组合）
     * @param line 文本行
     * @param timeGroup 时间组合
     * @return 提取的内容
     */
    private static String extractContent(String line, TimeGroup timeGroup) {
        if (timeGroup.matches.isEmpty()) {
            return line;
        }
        int startIndex = timeGroup.startIndex;
        int endIndex = timeGroup.endIndex;
        log.debug("移除时间段: [{},{}] = '{}'", startIndex, endIndex, line.substring(startIndex, endIndex));
        // 检查时间前面是否有负号需要一起移除
        if (startIndex > 0 && line.charAt(startIndex - 1) == '-') {
            startIndex--;
            log.debug("检测到前面有负号，调整起始位置为: {}", startIndex);
        }
        // 拼接内容
        StringBuilder result = new StringBuilder();
        if (startIndex > 0) {
            result.append(line, 0, startIndex);
        }
        if (endIndex < line.length()) {
            result.append(line.substring(endIndex));
        }
        return result.toString().trim();
    }

    /**
     * 解析时间单位字符串
     * @param unitStr 时间单位字符串
     * @return 时间单位枚举
     */
    private static MyTimeUnit parseTimeUnit(String unitStr) {
        for (MyTimeUnit timeUnit : MyTimeUnit.values()) {
            if (timeUnit.getValue().equals(unitStr)) {
                return timeUnit;
            }
        }
        return null;
    }

    /**
     * 时间匹配结果
     * @param startIndex 匹配的起始索引
     * @param endIndex 匹配的结束索引
     * @param value 匹配的值
     * @param unit 时间单位
     * @param originalText 匹配的原始文本
     */
    private record TimeMatch(int startIndex, int endIndex, double value, MyTimeUnit unit, String originalText) {
        @Override
        public String toString() {
            return String.format("%s[%d,%d]", originalText, startIndex, endIndex);
        }
    }

    /**
     * 时间组合
     * @param matches 时间匹配列表
     * @param startIndex 组的起始索引
     * @param endIndex 组的结束索引
     */
    private record TimeGroup(List<TimeMatch> matches, int startIndex, int endIndex) {
        private TimeGroup(List<TimeMatch> matches, int startIndex, int endIndex) {
            this.matches = new ArrayList<>(matches);
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        @Override
        public String toString() {
            return String.format("TimeGroup{%s, [%d,%d]}",
                    matches.stream().map(m -> m.originalText).collect(java.util.stream.Collectors.joining(",")),
                    startIndex, endIndex);
        }
    }
}
