package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ${NAME} substitution for scenario CSV cells, backed by a name/value CSV file per scenario file. */
public final class Variables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    private Variables() {}

    public static Map<String, String> load(File varsFile) throws IOException {
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map<String, String> row : Csv.read(varsFile)) {
            String name = row.get("name");
            if (name != null && !name.isBlank()) {
                vars.put(name.trim(), row.getOrDefault("value", ""));
            }
        }
        return vars;
    }

    public static void write(File varsFile, List<Map<String, String>> rows) throws IOException {
        Csv.write(varsFile, List.of("name", "value"), rows);
    }

    public static String substitute(String template, Map<String, String> vars) {
        if (template == null || template.indexOf('$') < 0) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static List<Map<String, String>> substituteRows(List<Map<String, String>> rows, Map<String, String> vars) {
        if (vars.isEmpty()) return rows;
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> substituted = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : row.entrySet()) {
                substituted.put(e.getKey(), substitute(e.getValue(), vars));
            }
            out.add(substituted);
        }
        return out;
    }
}
