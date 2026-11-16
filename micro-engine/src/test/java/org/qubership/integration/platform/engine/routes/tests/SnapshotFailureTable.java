package org.qubership.integration.platform.engine.routes.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SnapshotFailureTable {
    private static final List<String> HEADERS = List.of("Element", "Test", "Expected behavior", "Error");
    private static final int[] MAX_WIDTHS = {24, 64, 64, 96};

    private SnapshotFailureTable() {
    }

    static String format(List<Row> failures) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(HEADERS);
        failures.forEach(failure -> rows.add(List.of(failure.element(), failure.test(),
                failure.expectedBehavior(), failure.error())));
        int[] widths = new int[HEADERS.size()];
        for (List<String> row : rows) {
            for (int column = 0; column < widths.length; column++) {
                int length = Arrays.stream(row.get(column).split("\\R", -1))
                        .mapToInt(String::length).max().orElse(0);
                widths[column] = Math.min(MAX_WIDTHS[column], Math.max(widths[column], length));
            }
        }
        String border = border(widths);
        StringBuilder table = new StringBuilder("Failed snapshot scenarios\n").append(border);
        appendRow(table, HEADERS, widths);
        table.append(border);
        for (List<String> row : rows.subList(1, rows.size())) {
            appendRow(table, row, widths);
            table.append(border);
        }
        return table.toString();
    }

    private static String border(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }
        return border.append('\n').toString();
    }

    private static void appendRow(StringBuilder table, List<String> cells, int[] widths) {
        List<List<String>> columns = new ArrayList<>();
        for (int column = 0; column < widths.length; column++) {
            columns.add(wrap(cells.get(column), widths[column]));
        }
        int height = columns.stream().mapToInt(List::size).max().orElse(0);
        for (int line = 0; line < height; line++) {
            table.append('|');
            for (int column = 0; column < widths.length; column++) {
                List<String> values = columns.get(column);
                String value = line < values.size() ? values.get(line) : "";
                table.append(' ').append(value).append(" ".repeat(widths[column] - value.length() + 1)).append('|');
            }
            table.append('\n');
        }
    }

    private static List<String> wrap(String cell, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : cell.replace("\t", "    ").split("\\R", -1)) {
            String remaining = paragraph;
            while (remaining.length() > width) {
                int space = remaining.lastIndexOf(' ', width);
                int end = space > 0 ? space : width;
                lines.add(remaining.substring(0, end));
                remaining = remaining.substring(space > 0 ? end + 1 : end);
            }
            lines.add(remaining);
        }
        return lines;
    }

    record Row(String element, String test, String expectedBehavior, String error) {
    }
}
