package com.nip.app.common.utils;

import com.nip.app.pojo.richtext.TiptapNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 Tiptap table 节点序列化为 Markdown 表格
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 20:13 ✾
 **/
public class TableSerializeUtil {
    private TableSerializeUtil() {
    }

    public static String toMarkdown(TiptapNode tableNode) {
        if (!tableNode.hasContent()) return "";
        List<List<String>> rows = new ArrayList<>();
        boolean firstRowIsHeader = false;
        for (TiptapNode rowNode : tableNode.getContent()) {
            if (!"tableRow".equals(rowNode.getType())) continue;
            if (!rowNode.hasContent()) continue;
            List<String> row = new ArrayList<>();
            boolean hasHeaderCell = false;
            for (TiptapNode cellNode : rowNode.getContent()) {
                if (!"tableCell".equals(cellNode.getType())
                        && !"tableHeader".equals(cellNode.getType())) continue;
                if ("tableHeader".equals(cellNode.getType())) hasHeaderCell = true;
                String cellText = TextExtractUtil.extractPlainText(cellNode)
                        .replace("|", "\\|")
                        .replace("\n", " ")
                        .trim();
                row.add(cellText);
            }
            rows.add(row);
            if (rows.size() == 1 && hasHeaderCell) firstRowIsHeader = true;
        }
        if (rows.isEmpty()) return "";
        // 对齐列宽
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] colWidths = new int[colCount];
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                colWidths[i] = Math.max(colWidths[i], row.get(i).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            sb.append(buildRow(rows.get(r), colCount, colWidths));
            if (r == 0) {
                // 首行分隔线
                sb.append(buildSeparator(colCount, colWidths, firstRowIsHeader));
            }
        }
        return sb.toString().trim();
    }

    private static String buildRow(List<String> cells, int colCount, int[] colWidths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < colCount; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            sb.append(" ").append(padRight(cell, colWidths[i])).append(" |");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String buildSeparator(int colCount, int[] colWidths, boolean isHeader) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < colCount; i++) {
            sb.append(isHeader ? " " + "-".repeat(Math.max(3, colWidths[i])) + " |"
                    : " " + "-".repeat(Math.max(3, colWidths[i])) + " |");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}