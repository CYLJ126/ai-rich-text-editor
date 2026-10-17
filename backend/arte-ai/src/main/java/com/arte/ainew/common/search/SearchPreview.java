package com.arte.ainew.common.search;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.search.ConversationSearch;
import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 搜索结果片段转换工具，将 ES 高亮内容转换为有界纯文本及高亮区间。
 * 输入须由 ES 高亮器进行 HTML 编码，仅识别受控高亮标记。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchPreview {

    private static final String OPEN = "<arte-hit>", CLOSE = "</arte-hit>";
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private SearchPreview() {
    }

    /**
     * 解析高亮标记并还原转义文本，按字素截断内容，同时裁剪、合并 UTF-16 高亮区间。
     */
    public static ConversationSearch.Snippet fromEncodedHighlight(String encoded, int maxChars) {
        ContractChecks.range(maxChars, "maxChars", 1, 800);
        if (encoded == null || encoded.isEmpty()) return new ConversationSearch.Snippet("", List.of());
        ContractChecks.require(encoded.length() <= 8_000_000, "Highlight exceeds source safety limit");
        var text = new StringBuilder();
        var ranges = new ArrayList<ConversationSearch.Highlight>();
        int offset = 0, start = -1;
        boolean malformed = false;
        while (offset < encoded.length()) {
            int open = encoded.indexOf(OPEN, offset), close = encoded.indexOf(CLOSE, offset);
            int next = open < 0 ? close : close < 0 ? open : Math.min(open, close);
            if (next < 0) {
                text.append(StringEscapeUtils.unescapeHtml4(encoded.substring(offset)));
                break;
            }
            text.append(StringEscapeUtils.unescapeHtml4(encoded.substring(offset, next)));
            if (next == open) {
                if (start >= 0) malformed = true;
                start = text.length();
                offset = next + OPEN.length();
            } else {
                if (start < 0) malformed = true;
                else if (start < text.length()) ranges.add(new ConversationSearch.Highlight(start, text.length()));
                start = -1;
                offset = next + CLOSE.length();
            }
        }
        if (start >= 0 || malformed) ranges.clear();
        int cut = 0, count = 0;
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) {
            if (count == maxChars || matcher.end() > 16384) break;
            count++;
            cut = matcher.end();
        }
        int limit = cut;
        var clipped = new ArrayList<ConversationSearch.Highlight>();
        for (var range : ranges) {
            if (range.start() >= limit) break;
            int end = Math.min(range.end(), limit);
            if (!clipped.isEmpty() && range.start() <= clipped.getLast().end()) {
                var previous = clipped.removeLast();
                clipped.add(new ConversationSearch.Highlight(previous.start(), Math.max(previous.end(), end)));
            } else clipped.add(new ConversationSearch.Highlight(range.start(), end));
        }
        return new ConversationSearch.Snippet(text.substring(0, limit), clipped);
    }
}
