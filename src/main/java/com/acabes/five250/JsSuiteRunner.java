package com.acabes.five250;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.io.File;
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
 * Runs a standalone {@code <name>.js} script (project/scripts/<name>.js) as its own top-level
 * entity, fully decoupled from any one suite - a script has no suite of its own, only whichever
 * suites it explicitly pulls in via {@code importSuite(flow, file)}. However many {@code
 * execute()} calls the script makes, across however many imported suites, in whatever order or
 * loops it writes, they all accumulate into ONE ScenarioResult - every check, extracted value,
 * and captured screen-step, in the order they actually happened - so nothing downstream (results
 * CSV, extracted dumps, replay JSON, the GUI's Results/Replay display) needs to know a
 * JS-orchestrated run even exists; it's written out exactly like any other run's result list,
 * under a "scripts" pseudo-flow bucket (see HttpApi.handleScriptsRun).
 *
 * Globals bound into the script:
 *   - {@code connect(host, port, ssl)} - creates this run's session from scratch (same underlying
 *     call the Terminal tab's Connect button and a suite's "connect" row make), a no-op if the
 *     session already exists and is actually connected. A script is self-contained by default -
 *     the generated new-script template always opens with this - but calling it is optional: a
 *     script can still be run against a session connected ahead of time some other way.
 *   - {@code disconnect()} - closes and deregisters this run's session. The generated template
 *     always closes with this in a {@code finally}, so a script that creates its own session also
 *     always cleans it up, pass or fail - but it's a plain function call, not magic: skip it (or
 *     guard it) if a script is meant to leave its session open for something after it.
 *   - {@code importSuite(flow, file)} - loads suites/<flow>/<file>.csv (+ its own <file>.vars.csv)
 *     fresh off disk and returns a suite object exposing:
 *       - {@code .vars} - a live, two-way bound object over that suite's OWN vars map: {@code
 *         mySuite.vars.username = 'abc'} before an {@code execute()} call feeds that value into
 *         substitution for that call; an "extract" step inside a range writes back into this same
 *         map, readable immediately after. Two different imported suites never share a vars map.
 *       - {@code .steps(a, b)} - a range over that suite's own rows. Each of a/b is either a
 *         1-based row position in its CSV file (matching what the Suites explorer's Steps table
 *         shows for that file) or a string naming that row's own "id" cell (the CSV's optional id
 *         column - blank on most rows, set only where a script needs to name a boundary, e.g.
 *         {@code mySuite.steps("login", "after-login")}) - not case/step column values, so either
 *         addressing avoids the ambiguity of a file with multiple case groups. Mixing a numeric
 *         and a string argument is fine. An id must be unique across the whole file -
 *         importSuite() throws immediately if it isn't, rather than letting steps() silently
 *         resolve to the wrong row later.
 *       - {@code .all()} - shorthand for {@code .steps(1, <row count>)}, the common "just run this
 *         whole suite" case.
 *   - {@code execute(range)} - runs a range against the terminal right now: substitutes {@code
 *     ${NAME}} in those rows against whichever suite's vars map that range came from (fresh every
 *     call, so a script-set var always takes effect), then calls {@link StepActions#executeAction}
 *     for each row in order. Works identically for a range from any imported suite - the range
 *     itself carries which suite and which vars map it belongs to (see RowRange), so execute()
 *     never needs to be told which suite is "current". Resolves the live Terminal fresh from
 *     SessionService on every call (rather than once up front) since connect() may not have run
 *     yet at bind time - throws a clear "call connect() first" style error if there's still no
 *     session by the time a range actually needs to run.
 *   - {@code console.log}/{@code console.error} - bound to stderr, visible alongside Progress
 *     output in the CLI.
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

    /** One {@code suite.steps(a, b)} result: the row slice plus its starting absolute position
     * (so execute() can report/label each row by its real position in its file) AND which suite
     * it came from - its own vars map and name - so a single global execute() can run ranges from
     * any number of different imported suites without needing to be told which one is "current". */
    private static final class RowRange {
        final String suiteName;
        final Map<String, String> vars;
        final int startIndex; // 0-based index into the full ordered row list
        final List<Map<String, String>> rows;

        RowRange(String suiteName, Map<String, String> vars, int startIndex, List<Map<String, String>> rows) {
            this.suiteName = suiteName;
            this.vars = vars;
            this.startIndex = startIndex;
            this.rows = rows;
        }
    }

    /** @param suitesRoot the active project's suites/ directory - importSuite(flow, file) resolves
     *                    suitesRoot/flow/file.csv (+ file.vars.csv) against it.
     *  @param sessionService/sessionId - which session connect()/disconnect()/execute() act on;
     *                    NOT pre-resolved to a Terminal, since the session may not exist yet at
     *                    call time (that's the point of connect() existing at all). */
    static ScenarioResult run(SessionService sessionService, String sessionId, String scriptName,
                               File suitesRoot, String jsSource) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("case", scriptName);
        ScenarioResult result = new ScenarioResult(summary);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "js-script-" + scriptName);
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
                bindings.putMember("connect", (ProxyExecutable) args -> connect(sessionService, sessionId, args));
                bindings.putMember("disconnect", (ProxyExecutable) args -> { sessionService.disconnect(sessionId); return null; });
                bindings.putMember("execute", (ProxyExecutable) args ->
                    executeRange(sessionService, sessionId, result, scriptName, args));
                bindings.putMember("importSuite", (ProxyExecutable) args -> importSuite(suitesRoot, args));
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
                result.screenOnFailure = sessionService.getSession(sessionId).snapshot();
            } catch (Exception ignored) {
                // session may not exist (never connected, or already disconnected) - nothing more to capture
            }
        }
        return result;
    }

    private static Object connect(SessionService sessionService, String sessionId, Value[] args) {
        if (sessionService.isActuallyConnected(sessionId)) return null; // already live - a no-op, not an error
        if (args.length < 1 || !args[0].isString()) {
            throw new RuntimeException("connect(host, port, ssl) needs at least a host string argument");
        }
        String host = args[0].asString();
        long port = args.length > 1 && args[1].fitsInLong() ? args[1].asLong() : 23;
        boolean ssl = args.length > 2 && args[2].isBoolean() && args[2].asBoolean();

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", "connect");
        req.put("sessionId", sessionId);
        req.put("host", host);
        req.put("port", port);
        req.put("ssl", ssl);
        Map<String, Object> resp = sessionService.handle(req);
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            throw new RuntimeException("connect() failed: " + resp.get("error"));
        }
        return null;
    }

    private static Object importSuite(File suitesRoot, Value[] args) {
        if (args.length < 2 || !args[0].isString() || !args[1].isString()) {
            throw new RuntimeException("importSuite(flow, file) needs two string arguments");
        }
        String flow = args[0].asString();
        String file = args[1].asString();
        File flowDir = new File(suitesRoot, flow);
        File csvFile = new File(flowDir, file + ".csv");
        if (!csvFile.isFile()) {
            throw new RuntimeException("importSuite: no such suite '" + flow + "/" + file + "' (looked for " + csvFile + ")");
        }
        List<Map<String, String>> rows;
        Map<String, String> vars;
        try {
            rows = Csv.read(csvFile);
            vars = new LinkedHashMap<>(Variables.load(new File(flowDir, file + ".vars.csv")));
        } catch (Exception e) {
            throw new RuntimeException("importSuite: failed to load '" + flow + "/" + file + "': " + e.getMessage());
        }
        return buildSuiteObject(file, rows, vars);
    }

    private static Object executeRange(SessionService sessionService, String sessionId, ScenarioResult result,
                                        String scriptName, Value[] args) {
        if (args.length == 0 || !args[0].isHostObject() || !(args[0].asHostObject() instanceof RowRange)) {
            throw new RuntimeException("execute() expects a range returned by <suite>.steps(a, b) or <suite>.all()");
        }
        Terminal t;
        try {
            t = sessionService.getSession(sessionId);
        } catch (RuntimeException e) {
            throw new RuntimeException("execute() needs a live session - call connect(host, port) first: " + e.getMessage());
        }
        RowRange range = (RowRange) args[0].asHostObject();
        List<Map<String, String>> substituted = Variables.substituteRows(range.rows, range.vars);

        for (int i = 0; i < substituted.size(); i++) {
            Map<String, String> row = substituted.get(i);
            int stepNo = range.startIndex + i + 1; // 1-based absolute position in the suite's own file
            String action = row.getOrDefault("action", "").trim();
            String target = row.getOrDefault("target", "").trim();
            String value = row.getOrDefault("value", "");
            String label = range.suiteName + " step " + stepNo + ": " + action
                + (target.isEmpty() ? "" : " " + target) + (value.isEmpty() ? "" : " = " + value);
            Progress.report(scriptName + " - " + label);
            StepActions.executeAction(t, result, range.vars, row, stepNo, label);
        }
        return null;
    }

    private static ProxyObject buildSuiteObject(String suiteName, List<Map<String, String>> ordered, Map<String, String> vars) {
        // A duplicate id should already have been rejected when the suite was last saved (see
        // StepIds.validateUnique in HttpApi's PUT /api/scenarios) - this is a safety net for a
        // file that reached disk some other way (hand-edited, copied in, written before this
        // check existed), so importSuite() never silently resolves an ambiguous id.
        StepIds.validateUnique(ordered);

        // id -> 1-based position, so steps() can resolve a string argument in O(1).
        Map<String, Integer> idToPosition = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            String id = ordered.get(i).getOrDefault("id", "").trim();
            if (!id.isEmpty()) idToPosition.put(id, i + 1);
        }

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
                throw new RuntimeException("steps(a, b) needs two arguments - a row number or a step id");
            }
            int a = resolveStepArg(args[0], suiteName, idToPosition, ordered.size());
            int b = resolveStepArg(args[1], suiteName, idToPosition, ordered.size());
            if (a < 1 || b < a || b > ordered.size()) {
                throw new RuntimeException("steps(" + a + ", " + b + ") is out of range for '" + suiteName + "' (" + ordered.size() + " rows)");
            }
            return new RowRange(suiteName, vars, a - 1, new ArrayList<>(ordered.subList(a - 1, b)));
        };

        ProxyExecutable allFn = args -> new RowRange(suiteName, vars, 0, new ArrayList<>(ordered));

        return new ProxyObject() {
            public Object getMember(String key) {
                if (key.equals("vars")) return varsProxy;
                if (key.equals("steps")) return stepsFn;
                if (key.equals("all")) return allFn;
                return null;
            }

            public Object getMemberKeys() {
                return new String[]{"vars", "steps", "all"};
            }

            public boolean hasMember(String key) {
                return key.equals("vars") || key.equals("steps") || key.equals("all");
            }

            public void putMember(String key, Value value) {
                throw new RuntimeException("cannot assign to an imported suite's own '" + key + "' - set values through .vars instead");
            }
        };
    }

    /** Resolves one {@code steps(a, b)} argument to a 1-based row position: a plain number is used
     * as-is, a string is looked up in that suite's id -> position map (built once by
     * buildSuiteObject from the CSV's optional "id" column). Anything else, or an unknown id,
     * throws immediately with the suite name for context, rather than steps() failing later with
     * a confusing out-of-range error. */
    private static int resolveStepArg(Value arg, String suiteName, Map<String, Integer> idToPosition, int rowCount) {
        if (arg.isNumber()) {
            return arg.asInt();
        }
        if (arg.isString()) {
            String id = arg.asString();
            Integer position = idToPosition.get(id);
            if (position == null) {
                throw new RuntimeException("steps(): no step with id '" + id + "' in suite '" + suiteName
                    + "' - known ids: " + idToPosition.keySet());
            }
            return position;
        }
        throw new RuntimeException("steps(): each argument must be a row number or a string step id, got: " + arg);
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
}
