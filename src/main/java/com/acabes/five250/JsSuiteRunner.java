package com.acabes.five250;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a suite's {@code <name>.js} file as the actual entry point, instead of GenericStepFlow
 * walking every CSV row/case automatically. However many {@code execute()} calls the script
 * makes, in whatever order or loops it writes, they all accumulate into ONE ScenarioResult -
 * every check, extracted value, and captured screen-step, in the order they actually happened -
 * so nothing downstream (results CSV, extracted dumps, replay JSON, the GUI's Results/Replay
 * display) needs to know a JS-orchestrated run even exists.
 *
 * The script gets a global object named after the suite's base filename (sanitized to a valid JS
 * identifier - see {@link #sanitizeIdentifier}) exposing:
 *   - {@code .vars} - a live, two-way bound object over the SAME map {@code ${NAME}} substitution
 *     reads from: {@code suiteX.vars.username = 'abc'} before an {@code execute()} call feeds
 *     that value into substitution for that call; an "extract" step inside a range writes back
 *     into this same map (see StepActions.executeAction), readable immediately after.
 *   - {@code .steps(a, b)} - a range over the suite's rows, addressed by absolute 1-based row
 *     position in the CSV file (not by case/step column values - recorded suites are just "a
 *     sequence of rows", and file position avoids ambiguity when a file has multiple case
 *     groups).
 * A global {@code execute(range)} function runs that range against the terminal right now:
 * substitutes {@code ${NAME}} in those rows against the CURRENT vars map (fresh every call, so a
 * script-set var always takes effect), then calls {@link StepActions#executeAction} for each row
 * in order. {@code console.log}/{@code console.error} are bound to stderr, visible alongside
 * Progress output in the CLI.
 *
 * Sandboxed with {@code HostAccess.EXPLICIT} (only the specific bound objects/functions are
 * reachable - no arbitrary Java classes) and a denied class lookup (no {@code Java.type(...)});
 * necessarily a bigger bridge than JsCondition's pure boolean sandbox, since the script has to
 * call back into Java to drive the terminal at all. A whole-script wall-clock timeout is enforced
 * by the calling thread force-cancelling the Context ({@code context.close(true)}) - a real
 * cross-thread cancellation (verified empirically in this session), unlike Nashorn's unreliable
 * {@code Thread.interrupt()}.
 */
final class JsSuiteRunner {

    private static final long TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes - a whole script, not one condition

    private JsSuiteRunner() {}

    /** One {@code suiteX.steps(a, b)} result: the row slice plus its starting absolute position,
     * so execute() can report/label each row by its real position in the file, not just its
     * position within this particular slice. */
    private static final class RowRange {
        final int startIndex; // 0-based index into the full ordered row list
        final List<Map<String, String>> rows;

        RowRange(int startIndex, List<Map<String, String>> rows) {
            this.startIndex = startIndex;
            this.rows = rows;
        }
    }

    static ScenarioResult run(Terminal t, String suiteName, List<Map<String, String>> rows,
                               Map<String, String> vars, String jsSource) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("case", suiteName);
        summary.put("steps", String.valueOf(rows.size()));
        ScenarioResult result = new ScenarioResult(summary);

        List<Map<String, String>> ordered = new ArrayList<>(rows); // file order, as authored/recorded
        String identifier = sanitizeIdentifier(suiteName);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "js-suite-" + suiteName);
            th.setDaemon(true);
            return th;
        });
        Context context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowHostClassLookup(name -> false)
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        try {
            Future<Object> future = executor.submit(() -> {
                Value bindings = context.getBindings("js");
                bindings.putMember("console", buildConsole());
                bindings.putMember(identifier, buildSuiteObject(ordered, vars));
                bindings.putMember("execute", (ProxyExecutable) args ->
                    executeRange(t, vars, result, suiteName, ordered.size(), args));
                return context.eval("js", jsSource);
            });

            try {
                future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                context.close(true);
                throw new RuntimeException("script exceeded " + (TIMEOUT_MS / 1000) + "s - likely an infinite loop");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("script error: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
            }
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            try {
                context.close();
            } catch (Exception ignored) {
                // already closed via the timeout branch above
            }
            executor.shutdownNow();
        }

        if (!result.passed() && result.screenOnFailure == null) {
            try {
                result.screenOnFailure = t.snapshot();
            } catch (Exception ignored) {
                // terminal may be disconnected after a failure; nothing more we can capture
            }
        }
        return result;
    }

    private static Object executeRange(Terminal t, Map<String, String> vars, ScenarioResult result,
                                        String suiteName, int totalRows, Value[] args) {
        if (args.length == 0 || !args[0].isHostObject() || !(args[0].asHostObject() instanceof RowRange)) {
            throw new RuntimeException("execute() expects a range returned by <suite>.steps(a, b)");
        }
        RowRange range = (RowRange) args[0].asHostObject();
        List<Map<String, String>> substituted = Variables.substituteRows(range.rows, vars);

        for (int i = 0; i < substituted.size(); i++) {
            Map<String, String> row = substituted.get(i);
            int stepNo = range.startIndex + i + 1; // 1-based absolute position in the file
            String action = row.getOrDefault("action", "").trim();
            String target = row.getOrDefault("target", "").trim();
            String value = row.getOrDefault("value", "");
            String label = "step " + stepNo + "/" + totalRows + ": " + action
                + (target.isEmpty() ? "" : " " + target) + (value.isEmpty() ? "" : " = " + value);
            Progress.report("case " + suiteName + " - " + label);
            StepActions.executeAction(t, result, vars, row, stepNo, label);
        }
        return null;
    }

    private static ProxyObject buildSuiteObject(List<Map<String, String>> ordered, Map<String, String> vars) {
        ProxyObject varsProxy = new ProxyObject() {
            public Object getMember(String key) {
                return vars.get(key);
            }

            public Object getMemberKeys() {
                return vars.keySet().toArray(new String[0]);
            }

            public boolean hasMember(String key) {
                return vars.containsKey(key);
            }

            public void putMember(String key, Value value) {
                vars.put(key, value.isString() ? value.asString() : value.toString());
            }
        };

        ProxyExecutable stepsFn = args -> {
            if (args.length < 2) {
                throw new RuntimeException("steps(a, b) needs two row-number arguments");
            }
            int a = args[0].asInt();
            int b = args[1].asInt();
            if (a < 1 || b < a || b > ordered.size()) {
                throw new RuntimeException("steps(" + a + ", " + b + ") is out of range for a " + ordered.size() + "-row suite");
            }
            return new RowRange(a - 1, new ArrayList<>(ordered.subList(a - 1, b)));
        };

        return new ProxyObject() {
            public Object getMember(String key) {
                if (key.equals("vars")) return varsProxy;
                if (key.equals("steps")) return stepsFn;
                return null;
            }

            public Object getMemberKeys() {
                return new String[]{"vars", "steps"};
            }

            public boolean hasMember(String key) {
                return key.equals("vars") || key.equals("steps");
            }

            public void putMember(String key, Value value) {
                throw new RuntimeException("cannot assign to a suite's own '" + key + "' - set values through .vars instead");
            }
        };
    }

    private static ProxyObject buildConsole() {
        ProxyExecutable log = args -> {
            System.out.println("[js] " + joinArgs(args));
            System.out.flush();
            return null;
        };
        ProxyExecutable error = args -> {
            System.out.println("[js:error] " + joinArgs(args));
            System.out.flush();
            return null;
        };
        return new ProxyObject() {
            public Object getMember(String key) {
                if (key.equals("log")) return log;
                if (key.equals("error")) return error;
                return null;
            }

            public Object getMemberKeys() {
                return new String[]{"log", "error"};
            }

            public boolean hasMember(String key) {
                return key.equals("log") || key.equals("error");
            }

            public void putMember(String key, Value value) {
                throw new RuntimeException("cannot reassign console." + key);
            }
        };
    }

    private static String joinArgs(Value[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i].toString());
        }
        return sb.toString();
    }

    /** The suite's base filename becomes the script's global variable name, so it must be a
     * valid JS identifier - non-identifier characters (a suite named with hyphens, spaces, ...)
     * are replaced with underscores, and a leading digit gets an underscore prefix. */
    private static String sanitizeIdentifier(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString();
    }
}
