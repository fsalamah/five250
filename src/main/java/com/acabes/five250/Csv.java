package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC4180-ish CSV reader/writer. Header row required; column order preserved. */
public final class Csv {

    private Csv() {}

    public static List<Map<String, String>> read(File file) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!file.exists()) return rows;

        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<List<String>> records = parseRecords(content);
        if (records.isEmpty()) return rows;

        List<String> header = records.get(0);
        for (int i = 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            if (rec.size() == 1 && rec.get(0).isEmpty()) continue; // trailing blank line
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < rec.size() ? rec.get(c) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    public static void write(File file, List<Map<String, String>> rows) throws IOException {
        List<String> header = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        write(file, header, rows);
    }

    public static void write(File file, List<String> header, List<Map<String, String>> rows) throws IOException {
        file.getParentFile().mkdirs();

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
            out.println(joinRecord(header));
            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>();
                for (String col : header) values.add(row.getOrDefault(col, ""));
                out.println(joinRecord(values));
            }
        }
    }

    private static String joinRecord(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(fields.get(i)));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        boolean needsQuoting = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuoting) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static List<List<String>> parseRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = content.length();

        while (i < len) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r') {
                    i++;
                } else if (c == '\n') {
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
