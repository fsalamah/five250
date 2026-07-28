package com.acabes.five250;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a "<file>.d.ts" ambient TypeScript declaration alongside every scenario file, so a
 * paired "<file>.js" orchestrator (see JsSuiteRunner) gets real VSCode autocomplete for
 * "suiteX.vars.<name>" - not just a generic string-indexed object. Regenerated on every save of
 * either the steps table (PUT /api/scenarios) or the vars/data grid (PUT /api/scenario-data,
 * PUT /api/scenario-vars), so it never drifts from what the suite actually looks like right now.
 * Written even when no ".js" file exists yet, so creating one later in VSCode already has fresh
 * types waiting - and even for suites that never get a JS orchestrator, since it costs nothing to
 * keep current and does no harm sitting unused next to a plain CSV suite.
 *
 * The variable names it knows about come from three places, unioned: whatever's currently saved
 * in the suite's own vars/data grid, every ${NAME} placeholder actually referenced in any cell,
 * and every "extract" step's declared output name (value column) - since those are exactly the
 * vars a JS orchestrator's "suiteX.vars.<name>" would read after an execute() call, not just the
 * ones pre-declared as inputs.
 */
final class TypeDeclarations {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    private TypeDeclarations() {}

    static void write(File flowDir, String fileName, List<Map<String, String>> rows, Map<String, String> vars) {
        try {
            Set<String> names = new LinkedHashSet<>(vars.keySet());
            for (Map<String, String> row : rows) {
                for (String cell : row.values()) {
                    if (cell == null) continue;
                    Matcher m = PLACEHOLDER.matcher(cell);
                    while (m.find()) names.add(m.group(1));
                }
                String action = row.getOrDefault("action", "").trim().toLowerCase();
                if (action.equals("extract")) {
                    String out = row.getOrDefault("value", "").trim();
                    if (!out.isEmpty()) names.add(out);
                }
            }

            String identifier = JsSuiteRunner.sanitizeIdentifier(fileName);
            StringBuilder sb = new StringBuilder();
            sb.append("// Auto-generated whenever \"").append(fileName)
                .append("\" is saved (steps table or variables) - hand edits here will be overwritten.\n");
            sb.append("// Describes the global JsSuiteRunner binds for this suite's own <file>.js, if it has one.\n");
            sb.append("declare function execute(range: unknown): void;\n\n");
            sb.append("declare const ").append(identifier).append(": {\n");
            sb.append("  vars: {\n");
            for (String name : names) {
                sb.append("    ").append(quote(name)).append(": string;\n");
            }
            sb.append("  };\n");
            sb.append("  /** Rows a..b (1-based, inclusive) by absolute position in the CSV file. */\n");
            sb.append("  steps(a: number, b: number): unknown;\n");
            sb.append("};\n");

            File out = new File(flowDir, fileName + ".d.ts");
            Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Best-effort quality-of-life - never let a stale/missing .d.ts fail an actual save.
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
