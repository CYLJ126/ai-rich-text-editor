package com.arte.ainew.common.search;

import com.arte.ainew.common.validation.ContractChecks;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 搜索字符边界独立于模型 Token；保留合法 emoji 的连接符。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchText {

    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private SearchText() {
    }

    public static String query(String input) {
        ContractChecks.require(input != null && input.length() <= 8192, "Invalid search query");
        var value = stripWhitespace(input);
        int count = visibleLength(value);
        ContractChecks.require(count > 0 && count <= 128, "Search query must contain 1 to 128 visible characters");
        return value;
    }

    public static int visibleLength(String value) {
        int count = 0;
        var matcher = GRAPHEME.matcher(value);
        while (matcher.find()) {
            if (matcher.group().codePoints().anyMatch(SearchText::visible)) count++;
        }
        return count;
    }

    private static String stripWhitespace(String input) {
        int start = 0, end = input.length();
        while (start < end && whitespace(input.codePointAt(start)))
            start += Character.charCount(input.codePointAt(start));
        while (end > start && whitespace(input.codePointBefore(end)))
            end -= Character.charCount(input.codePointBefore(end));
        return input.substring(start, end);
    }

    private static boolean whitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean visible(int cp) {
        int type = Character.getType(cp);
        return !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)
                && type != Character.FORMAT && type != Character.CONTROL
                && type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                && type != Character.COMBINING_SPACING_MARK && type != Character.SURROGATE;
    }

    public static List<String> groups(List<String> ids) {
        if (ids == null) return List.of();
        ContractChecks.range(ids.size(), "groupIds.size", 0, 256);
        ids.forEach(id -> {
            ContractChecks.id(id, "groupId");
            ContractChecks.require(id.equals(stripWhitespace(id)) && visibleLength(id) > 0, "Invalid group ID");
        });
        return ids.stream().distinct().sorted().toList();
    }

    public static void requireGroupsAvailable(List<String> ids) {
        if (!ids.isEmpty()) throw new SearchFailure(SearchFailure.Code.GROUP_FILTER_NOT_AVAILABLE);
    }
}
