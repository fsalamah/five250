package com.acabes.five250;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local web server for the GUI: static files + a JSON API on top of SessionService
 * (terminal control) and the CSV/Flow scenario engine.
 */
public final class HttpApi {

    public static final int PORT = 25251;

    private static final DateTimeFormatter RUN_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final SessionService sessionService;
    private final RunTracker runTracker = new RunTracker();

    public HttpApi(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/rpc", this::handleRpc);
        server.createContext("/api/projects", this::handleProjects);
        server.createContext("/api/flows", this::handleFlows);
        server.createContext("/api/suites", this::handleSuites);
        server.createContext("/api/scenario-files", this::handleScenarioFiles);
        server.createContext("/api/scenarios/run-status", this::handleScenariosRunStatus);
        server.createContext("/api/scenarios/run", this::handleScenariosRun);
        server.createContext("/api/runs/active", this::handleRunsActive);
        server.createContext("/api/runs/cancel", this::handleRunsCancel);
        server.createContext("/api/scenarios/replay", this::handleScenarioReplay);
        server.createContext("/api/scenarios/last-run", this::handleScenarioLastRun);
        server.createContext("/api/scenario-vars", this::handleScenarioVars);
        server.createContext("/api/scenario-data", this::handleScenarioData);
        server.createContext("/api/scenarios", this::handleScenarios);
        server.createContext("/api/scripts/run", this::handleScriptsRun);
        server.createContext("/api/scripts/source", this::handleScriptsSource);
        server.createContext("/api/scripts", this::handleScripts);
        server.createContext("/", this::handleStatic);

        server.start();
        System.out.println("five250 GUI at http://127.0.0.1:" + PORT);
    }

    // ---------- /api/rpc : generic terminal control, same protocol as the TCP daemon ----------

    private void handleRpc(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405, Map.of("ok", false, "error", "POST only"));
            return;
        }
        Map<String, Object> req = Json.parseObject(readBody(ex));
        Map<String, Object> resp;
        try {
            resp = sessionService.handle(req);
        } catch (Throwable e) {
            resp = SessionService.errorResponse(e);
        }
        sendJson(ex, 200, resp);
    }

    // ---------- /api/projects : project workspace registry (list/create/open/current) ----------

    private void handleProjects(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.equals("/api/projects/current") && method.equals("GET")) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                try {
                    resp.put("project", ProjectRegistry.current().toMap());
                } catch (IllegalStateException e) {
                    resp.put("project", null); // no project open yet - GUI shows a "create/open" empty state
                }
                sendJson(ex, 200, resp);
                return;
            }

            if (path.equals("/api/projects/open") && method.equals("POST")) {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                ProjectRegistry.Project p = ProjectRegistry.open(String.valueOf(req.get("name")));
                sendJson(ex, 200, Map.of("ok", true, "project", p.toMap()));
                return;
            }

            if (path.equals("/api/projects") && method.equals("GET")) {
                List<Object> list = new ArrayList<>();
                for (ProjectRegistry.Project p : ProjectRegistry.list()) list.add(p.toMap());
                sendJson(ex, 200, Map.of("ok", true, "projects", list));
                return;
            }

            if (path.equals("/api/projects") && method.equals("POST")) {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                ProjectRegistry.Project p = ProjectRegistry.create(String.valueOf(req.get("name")));
                sendJson(ex, 200, Map.of("ok", true, "project", p.toMap()));
                return;
            }

            sendJson(ex, 404, Map.of("ok", false, "error", "no such projects route: " + method + " " + path));
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    // ---------- /api/flows ----------

    private void handleFlows(HttpExchange ex) throws IOException {
        List<Object> flows = new ArrayList<>();
        for (Flow f : FlowRegistry.all().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("columns", f.csvColumns());
            flows.add(m);
        }
        sendJson(ex, 200, Map.of("ok", true, "flows", flows));
    }

    // ---------- /api/scenario-files : the "project explorer" — list/create/delete/rename CSV files within a flow ----------

    private void handleScenarioFiles(HttpExchange ex) throws IOException {
        try {
            handleScenarioFilesInner(ex);
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    private void handleScenarioFilesInner(HttpExchange ex) throws IOException {
        switch (ex.getRequestMethod()) {
            case "GET": {
                Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
                String flowName = requireParam(query, "flow");
                File dir = flowDir(flowName);
                dir.mkdirs();
                List<Object> files = new ArrayList<>();
                File[] found = dir.listFiles((d, n) -> n.endsWith(".csv") && !n.endsWith(".results.csv")
                    && !n.endsWith(".vars.csv") && !n.endsWith(".data.csv"));
                if (found != null) {
                    java.util.Arrays.sort(found, java.util.Comparator.comparing(File::getName));
                    for (File f : found) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        String name = f.getName().substring(0, f.getName().length() - 4);
                        m.put("name", name);
                        m.put("rows", Csv.read(f).size());
                        files.add(m);
                    }
                }
                sendJson(ex, 200, Map.of("ok", true, "files", files));
                return;
            }
            case "POST": {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                String flowName = (String) req.get("flow");
                String name = safeName((String) req.get("name"));
                Flow flow = FlowRegistry.get(flowName);
                File file = scenarioFile(flowName, name);
                if (file.exists()) {
                    sendJson(ex, 409, Map.of("ok", false, "error", "File already exists: " + name));
                    return;
                }
                Csv.write(file, flow.csvColumns(), List.of());
                sendJson(ex, 200, Map.of("ok", true, "name", name));
                return;
            }
            case "PUT": {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                String flowName = (String) req.get("flow");
                String oldName = safeName((String) req.get("oldName"));
                String newName = safeName((String) req.get("newName"));
                File oldFile = scenarioFile(flowName, oldName);
                File newFile = scenarioFile(flowName, newName);
                if (!oldFile.exists()) {
                    sendJson(ex, 404, Map.of("ok", false, "error", "No such file: " + oldName));
                    return;
                }
                if (newFile.exists()) {
                    sendJson(ex, 409, Map.of("ok", false, "error", "File already exists: " + newName));
                    return;
                }
                oldFile.renameTo(newFile);
                sendJson(ex, 200, Map.of("ok", true, "name", newName));
                return;
            }
            case "DELETE": {
                Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
                String flowName = requireParam(query, "flow");
                String name = safeName(requireParam(query, "file"));
                File file = scenarioFile(flowName, name);
                file.delete();
                sendJson(ex, 200, Map.of("ok", true));
                return;
            }
            default:
                sendJson(ex, 405, Map.of("ok", false, "error", "method not supported"));
        }
    }

    // ---------- /api/scenarios : rows of one specific CSV file ----------

    private void handleScenarios(HttpExchange ex) throws IOException {
        try {
            handleScenariosInner(ex);
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    private void handleScenariosInner(HttpExchange ex) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
        String flowName = requireParam(query, "flow");
        String fileName = safeName(requireParam(query, "file"));
        File file = scenarioFile(flowName, fileName);

        switch (ex.getRequestMethod()) {
            case "GET": {
                List<Map<String, String>> rows = Csv.read(file);
                sendJson(ex, 200, Map.of("ok", true, "rows", rows, "columns", FlowRegistry.get(flowName).csvColumns()));
                return;
            }
            case "PUT": {
                List<Map<String, String>> rows = toStringRows(Json.parse(readBody(ex)));
                StepIds.validateUnique(rows);
                Csv.write(file, FlowRegistry.get(flowName).csvColumns(), rows);
                sendJson(ex, 200, Map.of("ok", true, "count", rows.size()));
                return;
            }
            default:
                sendJson(ex, 405, Map.of("ok", false, "error", "GET or PUT only"));
        }
    }

    // ---------- /api/scenario-vars : ${NAME} values for one scenario file ----------

    private void handleScenarioVars(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String flowName = requireParam(query, "flow");
            String fileName = safeName(requireParam(query, "file"));
            File file = varsFile(flowName, fileName);

            switch (ex.getRequestMethod()) {
                case "GET": {
                    List<Map<String, String>> rows = Csv.read(file);
                    sendJson(ex, 200, Map.of("ok", true, "rows", rows));
                    return;
                }
                case "PUT": {
                    List<Map<String, String>> rows = toStringRows(Json.parse(readBody(ex)));
                    Variables.write(file, rows);
                    Map<String, String> vars = new LinkedHashMap<>();
                    for (Map<String, String> row : rows) {
                        String name = row.get("name");
                        if (name != null && !name.isBlank()) vars.put(name.trim(), row.getOrDefault("value", ""));
                    }
                    DataDrivenRunner.onVarsSaved(flowDir(flowName), flowName, fileName, vars);
                    sendJson(ex, 200, Map.of("ok", true, "count", rows.size()));
                    return;
                }
                default:
                    sendJson(ex, 405, Map.of("ok", false, "error", "GET or PUT only"));
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    /**
     * /api/scenario-data : the data-driven grid backing "<file>.data.csv" directly - columns are
     * variable names, rows are value sets - so the GUI's Variables panel can edit that file's
     * actual shape (add a row = a new value set, add a column = a new variable) instead of a
     * separate name/value list. PUT also derives the single "current" vars.csv from the last row
     * (see DataDrivenRunner.saveGrid) and regenerates the <file>.bat/.sh pair.
     */
    private void handleScenarioData(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String flowName = requireParam(query, "flow");
            String fileName = safeName(requireParam(query, "file"));
            File file = DataDrivenRunner.dataFile(flowDir(flowName), fileName);

            switch (ex.getRequestMethod()) {
                case "GET": {
                    List<String> columns = Csv.readHeader(file);
                    List<Map<String, String>> rows = Csv.read(file);
                    // <file>.data.csv (the data-driven grid) doesn't exist for every suite - a
                    // suite whose vars were only ever written directly to <file>.vars.csv
                    // (hand-authored, or via the GUI's "save extracted vars as defaults" on
                    // Save-as-suite) has real values there with no matching .data.csv at all.
                    // Rather than show an empty grid, synthesize a one-column-per-variable,
                    // one-row grid straight from vars.csv - same shape the grid would produce if
                    // you'd entered those same values by hand as a single value set.
                    if (columns.isEmpty()) {
                        Map<String, String> vars = Variables.load(varsFile(flowName, fileName));
                        if (!vars.isEmpty()) {
                            columns = new ArrayList<>(vars.keySet());
                            rows = List.of(vars);
                        }
                    }
                    sendJson(ex, 200, Map.of("ok", true, "columns", columns, "rows", rows));
                    return;
                }
                case "PUT": {
                    Map<String, Object> body = Json.parseObject(readBody(ex));
                    List<String> columns = new ArrayList<>();
                    Object columnsRaw = body.get("columns");
                    if (columnsRaw instanceof List) {
                        for (Object c : (List<?>) columnsRaw) columns.add(String.valueOf(c));
                    }
                    List<Map<String, String>> rows = toStringRows(body.get("rows"));
                    DataDrivenRunner.saveGrid(flowDir(flowName), varsFile(flowName, fileName), flowName, fileName, columns, rows);
                    sendJson(ex, 200, Map.of("ok", true, "count", rows.size()));
                    return;
                }
                default:
                    sendJson(ex, 405, Map.of("ok", false, "error", "GET or PUT only"));
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    // ---------- /api/scenarios/run : starts a run in the background, returns a runId to poll ----------

    private void handleScenariosRun(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405, Map.of("ok", false, "error", "POST only"));
            return;
        }
        Map<String, Object> req = Json.parseObject(readBody(ex));
        String flowName = (String) req.get("flow");
        String fileName = safeName((String) req.get("file"));
        String sessionId = req.getOrDefault("sessionId", "default").toString();
        boolean disconnectOnFinish = Boolean.TRUE.equals(req.get("disconnectOnFinish"));
        // Configurable gap held after each step - see StepActions.executeAction's doc. Optional;
        // defaults to DEFAULT_STEP_DELAY_MS (200ms) when the caller doesn't specify one.
        long stepDelayMs = req.get("stepDelayMs") instanceof Number
            ? ((Number) req.get("stepDelayMs")).longValue() : StepActions.DEFAULT_STEP_DELAY_MS;

        try {
            Flow flow = FlowRegistry.get(flowName);
            ProjectRegistry.Project project = resolveProject(req); // resolved once, up front - see resolveProject()
            File csvFile = scenarioFile(project, flowName, fileName);
            List<Map<String, String>> rawRows = Csv.read(csvFile);
            Map<String, String> vars = new LinkedHashMap<>(Variables.load(varsFile(project, flowName, fileName)));
            Object varsOverride = req.get("vars"); // CLI/API caller can supply/override ${NAME} values externally
            if (varsOverride instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) varsOverride).entrySet()) {
                    vars.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }

            List<Map<String, String>> rows = flow.preprocess(flowDir(project, flowName), rawRows); // splice in any "include" steps
            // NOT substituted here - vars is now LIVE and substitution happens per-step, INSIDE
            // the run (GenericStepFlow.runGroup), so an "extract" step's output is visible to a
            // ${NAME} reference later in this same run, not just to a later, separate run. See
            // Flow.runGroup's doc. autoConnectIfNeeded still substitutes its one "connect" row
            // itself, against vars as they stand right now (nothing could have run yet anyway).
            rows = autoConnectIfNeeded(sessionId, rows, vars); // suite can create its own session via a "connect" step
            Terminal t = sessionService.getSession(sessionId); // now guaranteed to exist
            final List<Map<String, String>> finalRows = rows;
            int total = ScenarioRunner.countScenarios(flow, finalRows);

            String runId = runTracker.start(total, sessionId, flowName + "/" + fileName, "suite");
            RunTracker.RunState state = runTracker.get(runId);

            Thread runThread = new Thread(() -> {
                Progress.set(desc -> state.current = desc);
                try {
                    List<ScenarioResult> results = ScenarioRunner.run(flow, t, finalRows, vars, stepDelayMs,
                        r -> { state.results.add(r.toMap()); state.current = ""; });
                    writeRunArtifacts(project, flowName, fileName, results);
                    if (!state.cancelRequested) state.status = "done";
                } catch (Throwable e) {
                    if (!state.cancelRequested) {
                        state.status = "error";
                        state.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                } finally {
                    Progress.clear();
                    // Opt-in: closes the session no matter how the run ended (pass, fail, or even
                    // a run-level error) - for callers (CI, a data-driven .bat/.sh loop) that want
                    // a clean disconnect every time without a suite having to end with its own
                    // "disconnect" step. Off by default - a suite with no disconnect step should
                    // leave its session exactly as the suite itself left it.
                    if (disconnectOnFinish) {
                        try { sessionService.disconnect(sessionId); } catch (Throwable ignored) {}
                    }
                }
            }, "scenario-run-" + runId);
            state.thread = runThread;
            runThread.start();

            sendJson(ex, 200, Map.of("ok", true, "runId", runId, "total", total));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    /** Every run gets its own timestamped copy so successive runs never clobber each other's
     * history; a fixed-name "latest" copy is kept alongside purely for convenience (grep/tail
     * without hunting for the newest timestamp) and because /api/scenarios/replay's simple
     * flow/file/index query resolves against the untimestamped replay path. Shared by both a
     * plain suite run (flowName = a real Flow name) and a standalone script run (flowName =
     * "scripts", a pseudo-bucket - see handleScriptsRun) - either way it's just one
     * ScenarioResult list to write out identically. */
    private void writeRunArtifacts(ProjectRegistry.Project project, String flowName, String fileName,
                                    List<ScenarioResult> results) throws IOException {
        String ts = LocalDateTime.now().format(RUN_TIMESTAMP);
        File resultsDir = new File(new File(project.root, "results"), flowName);
        File extractedDir = new File(new File(project.root, "extracted"), flowName);
        File failuresRoot = new File(project.root, "docs/samples/failures");
        File replaysRoot = new File(project.root, "docs/samples/replays");
        ScenarioRunner.writeResults(new File(resultsDir, fileName + ".results.csv"), results);
        ScenarioRunner.writeResults(new File(resultsDir, fileName + ".results." + ts + ".csv"), results);
        ScenarioRunner.writeFailureDumps(new File(new File(failuresRoot, flowName), fileName), results);
        ScenarioRunner.writeFailureDumps(new File(new File(new File(failuresRoot, flowName), fileName), ts), results);
        ScenarioRunner.writeReplays(new File(new File(replaysRoot, flowName), fileName), results);
        ScenarioRunner.writeReplays(new File(new File(new File(replaysRoot, flowName), fileName), ts), results);
        ScenarioRunner.writeLastRunInfo(new File(new File(replaysRoot, flowName), fileName),
            LocalDateTime.parse(ts, RUN_TIMESTAMP).toString(), results.size());
        ScenarioRunner.writeExtractedDumps(extractedDir, fileName, null, results);
        ScenarioRunner.writeExtractedDumps(extractedDir, fileName, ts, results);
    }

    /**
     * If a suite has a row with action="connect" (custom-steps: target=host, value=port,
     * expected="true" for SSL), and the session doesn't already exist, connects it — so a
     * suite can create its own session from scratch, no prior manual Connect click needed.
     * Either way, strips every row belonging to that row's case, so it never reaches a Flow
     * as an unrecognized action.
     */
    private List<Map<String, String>> autoConnectIfNeeded(String sessionId, List<Map<String, String>> rows, Map<String, String> vars) {
        Map<String, String> connectRow = null;
        for (Map<String, String> row : rows) {
            if ("connect".equalsIgnoreCase(row.getOrDefault("action", "").trim())) {
                connectRow = row;
                break;
            }
        }
        if (connectRow == null) return rows;
        // Rows reaching here are no longer pre-substituted whole-file (see handleScenariosRun) -
        // this is the one row read before any step has actually run, so substituting it against
        // vars as they stand right now is equivalent to what the old up-front pass would have done.
        connectRow = Variables.substitute(connectRow, vars);

        // A prior run's own "disconnect" step (GenericStepFlow) closes the Terminal's socket but
        // has no way to deregister it here — leaving a stale, dead entry that map-membership
        // alone would mistake for "already connected", skipping reconnection and dooming every
        // run after the one that disconnected. Clear it out so a fresh connect can replace it.
        boolean alreadyConnected = sessionService.isActuallyConnected(sessionId);
        if (!alreadyConnected) {
            sessionService.forget(sessionId);
            String host = connectRow.getOrDefault("target", "").trim();
            if (host.isEmpty()) throw new RuntimeException("connect step has no target host");
            long port = 23;
            try {
                String v = connectRow.getOrDefault("value", "").trim();
                if (!v.isEmpty()) port = Long.parseLong(v);
            } catch (NumberFormatException e) {
                throw new RuntimeException("connect step's value must be a port number, got: " + connectRow.get("value"));
            }
            boolean ssl = "true".equalsIgnoreCase(connectRow.getOrDefault("expected", "").trim());

            Map<String, Object> connectReq = new LinkedHashMap<>();
            connectReq.put("cmd", "connect");
            connectReq.put("sessionId", sessionId);
            connectReq.put("host", host);
            connectReq.put("port", port);
            connectReq.put("ssl", ssl);
            Map<String, Object> resp = sessionService.handle(connectReq);
            if (!Boolean.TRUE.equals(resp.get("ok"))) {
                throw new RuntimeException("auto-connect failed: " + resp.get("error"));
            }
        }

        String skipCase = connectRow.get("case");
        List<Map<String, String>> remaining = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!java.util.Objects.equals(row.get("case"), skipCase)) remaining.add(row);
        }
        return remaining;
    }

    private void handleScenariosRunStatus(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            RunTracker.RunState state = runTracker.get(requireParam(query, "runId"));
            sendJson(ex, 200, ok(state.toMap()));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    /** GET /api/runs/active : every currently-running suite/script across all sessions - the side
     * panel polls this to show "session X is executing suite/script Y" and offer a Kill button. */
    private void handleRunsActive(HttpExchange ex) throws IOException {
        try {
            sendJson(ex, 200, Map.of("ok", true, "runs", runTracker.listActive()));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    /** POST /api/runs/cancel {runId} : kills a running suite/script - see RunTracker.cancel() for
     * why this works by force-disconnecting the run's session rather than a cooperative flag. */
    private void handleRunsCancel(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405, Map.of("ok", false, "error", "POST only"));
            return;
        }
        try {
            Map<String, Object> req = Json.parseObject(readBody(ex));
            String runId = String.valueOf(req.get("runId"));
            boolean cancelled = runTracker.cancel(runId, sessionService);
            sendJson(ex, 200, Map.of("ok", true, "cancelled", cancelled));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    /** Fetches one persisted replay (step-by-step screen captures) written after a run finished. */
    private void handleScenarioReplay(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String flowName = requireParam(query, "flow");
            String fileName = safeName(requireParam(query, "file"));
            int index = Integer.parseInt(requireParam(query, "index"));
            File f = new File(new File(new File(ProjectRegistry.replaysDir(), flowName), fileName), "row-" + index + ".json");
            if (!f.exists()) {
                sendJson(ex, 404, Map.of("ok", false, "error", "No replay found: " + f));
                return;
            }
            String json = java.nio.file.Files.readString(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] withOk = ("{\"ok\":true,\"replay\":" + json + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, withOk.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(withOk);
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    /** {timestamp, count} for whichever run's replay dump is currently sitting in the untimestamped
     * "latest" directory (see writeRunArtifacts/ScenarioRunner.writeLastRunInfo) - lets the GUI
     * offer "replay the last run" (and show when it happened) after a page reload or on a suite/
     * script it never watched run live in this browser at all, not just right after clicking Run. */
    private void handleScenarioLastRun(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String flowName = requireParam(query, "flow");
            String fileName = safeName(requireParam(query, "file"));
            File f = new File(new File(new File(ProjectRegistry.replaysDir(), flowName), fileName), "last-run.json");
            if (!f.exists()) {
                sendJson(ex, 200, Map.of("ok", true, "found", false));
                return;
            }
            String json = java.nio.file.Files.readString(f.toPath());
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] withOk = ("{\"ok\":true,\"found\":true,\"lastRun\":" + json + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, withOk.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(withOk);
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    // ---------- /api/suites : every suite across every flow, for the Script editor's "+ Import Suite" picker ----------

    private void handleSuites(HttpExchange ex) throws IOException {
        try {
            List<Object> out = new ArrayList<>();
            for (Flow f : FlowRegistry.all().values()) {
                File dir = flowDir(f.name());
                File[] found = dir.listFiles((d, n) -> n.endsWith(".csv") && !n.endsWith(".results.csv")
                    && !n.endsWith(".vars.csv") && !n.endsWith(".data.csv"));
                if (found == null) continue;
                java.util.Arrays.sort(found, java.util.Comparator.comparing(File::getName));
                for (File file : found) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("flow", f.name());
                    m.put("file", file.getName().substring(0, file.getName().length() - 4));
                    out.add(m);
                }
            }
            sendJson(ex, 200, Map.of("ok", true, "suites", out));
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    // ---------- /api/scripts : standalone scripts (project/scripts/<name>.js) - list/create/delete ----------

    /** Fixed ambient TypeScript declaration returned for every script's Monaco editor - unlike the
     * old per-suite ".d.ts" (deleted along with the suite/script coupling it existed for), this
     * never varies: importSuite()'s return shape is the same regardless of which suite you import,
     * so there's nothing to regenerate per script and nothing to keep in sync on disk. */
    private static final String SCRIPT_DECLARATIONS =
        "/** Creates this run's session (no-op if already connected). Same call the Terminal tab's\n"
        + " *  Connect button and a suite's \"connect\" row make. port defaults to 23, ssl to false. */\n"
        + "declare function connect(host: string, port?: number, ssl?: boolean): void;\n"
        + "/** Closes and deregisters this run's session. */\n"
        + "declare function disconnect(): void;\n"
        + "declare function execute(range: unknown): void;\n"
        + "type Suite = {\n"
        + "  /** Live-bound to that suite's own ${NAME} values - read after an execute() extract, or set before one. */\n"
        + "  vars: { [name: string]: string };\n"
        + "  /** Rows a..b, inclusive. Each of a/b is a 1-based row position in that suite's own\n"
        + "   *  CSV file, OR a string naming that row's own \"id\" cell (the CSV's optional id\n"
        + "   *  column) - mixing a number and a string is fine, e.g. steps(\"login\", 12). */\n"
        + "  steps(a: number | string, b: number | string): unknown;\n"
        + "  /** Every row in the suite, in file order - shorthand for steps(1, <row count>). */\n"
        + "  all(): unknown;\n"
        + "};\n"
        // importSuite itself is deliberately NOT declared here. index.html's refreshScriptVarsLib
        // registers it in a separate, per-script extra lib instead, as a set of overloads keyed on
        // the exact importSuite("flow","file") calls found in the script's own source - one
        // literal-typed overload per real import (giving named ${NAME} completion on .vars), plus
        // a final `(flow: string, file: string): Suite` fallback for anything else. All of that has
        // to live together in one file/declaration group for TypeScript to try the overloads in a
        // predictable order; splitting the generic signature in here and the specific ones there
        // previously caused real breakage (see that function's own comment for the postmortem).
        + "/** Writes any JSON-shaped value to extracted/scripts/<this script>.<name>.json -\n"
        + " *  overwrites on every call, not an append. */\n"
        + "declare function saveJson(name: string, data: unknown): void;\n"
        + "/** Writes rows (each a flat object; columns come from the first row's own keys) to\n"
        + " *  extracted/scripts/<this script>.<name>.csv - overwrites on every call. */\n"
        + "declare function saveCsv(name: string, rows: Array<{ [column: string]: unknown }>): void;\n"
        + "/** Same file/shape as saveCsv, but ADDS to whatever's already there instead of overwriting -\n"
        + " *  for accumulating one row set per page while paging through a multi-page subfile list. */\n"
        + "declare function appendCsv(name: string, rows: Array<{ [column: string]: unknown }>): void;\n"
        + "/** Read-only - values this run was invoked with (\"five250 run-script <name> --var\n"
        + " *  NAME=VALUE\", or the GUI's Run dialog). args.MISSING reads as undefined, not an\n"
        + " *  error. Not the same thing as an imported suite's own .vars - a script has no suite\n"
        + " *  of its own, and this is supplied from OUTSIDE the script's source, not a CSV. */\n"
        + "declare const args: { [name: string]: string };\n";

    /** A new script starts from this template rather than empty - self-contained by default:
     * opens its own session, always closes it in a finally (pass or fail), and shows exactly
     * where an importSuite() call and its execute() calls go, plus every other script global
     * built up over this project's history: login, id-addressed step ranges, cross-suite
     * variable hand-off, CLI/GUI args, console output, and saveJson/saveCsv/appendCsv. Edit or
     * delete any of it freely - this is a starting point, not an enforced shape. Every claim in
     * these comments was verified against a real running script before being written here (not
     * just written and assumed correct) - see suites/custom-steps/signon-common.csv and
     * suites/custom-steps/js_orchestrator_demo.csv for the suites referenced below. */
    private static final String NEW_SCRIPT_TEMPLATE =
        "connect('pub400.com', 23);\n"
        + "try {\n"
        + "  // --- Logging in ---\n"
        + "  // suites/custom-steps/signon-common.csv is a small, reusable suite: type user,\n"
        + "  // type password, ENTER, ENTER. Set its vars BEFORE execute() - substitution\n"
        + "  // happens fresh on every execute() call, against whatever's currently in .vars.\n"
        + "  // const signon = importSuite('custom-steps', 'signon-common');\n"
        + "  // signon.vars.USER = 'your-pub400-user';\n"
        + "  // signon.vars.PASSWORD = 'your-pub400-password';\n"
        + "  // execute(signon.all());\n"
        + "\n"
        + "  // --- Running a range of steps by name instead of row number ---\n"
        + "  // A suite's CSV can give any row an \"id\" (its own optional id column, free-form\n"
        + "  // text, unique per file). steps(a, b) takes a row number OR that id string for\n"
        + "  // either end, mixed freely - so a range survives the suite being edited later\n"
        + "  // instead of breaking because row numbers shifted. all() is steps(1, <row count>).\n"
        + "  // suites/custom-steps/js_orchestrator_demo.csv has \"login-start\"..\"after-login\":\n"
        + "  // const demo = importSuite('custom-steps', 'js_orchestrator_demo');\n"
        + "  // demo.vars.CMD = 'WRKACTJOB';\n"
        + "  // execute(demo.steps('login-start', 'after-login'));\n"
        + "\n"
        + "  // --- Using variables across different suites ---\n"
        + "  // Each importSuite() call returns its OWN .vars map - setting one suite's vars\n"
        + "  // never touches a different suite's, even if both use the same ${NAME}. To carry\n"
        + "  // a value from one suite into another, copy it across explicitly:\n"
        + "  // const mySuite = importSuite('custom-steps', '<suite-name>');\n"
        + "  // mySuite.vars.SOME_VAR = signon.vars.USER;   // explicit hand-off, not automatic\n"
        + "  // execute(mySuite.all());\n"
        + "\n"
        + "  // --- Reading a value an \"extract\" step (rows:/table: included) just pulled off\n"
        + "  // the screen --- an extract step inside an executed range writes straight into that\n"
        + "  // suite's own .vars, readable immediately after the execute() call returns:\n"
        + "  // console.log('landed on: ' + demo.vars.title);\n"
        + "\n"
        + "  // --- Command-line/GUI arguments ---\n"
        + "  // `five250 run-script <this-script> --var HOST=foo` (or the GUI's Run dialog) makes\n"
        + "  // args.HOST available here - read-only, a missing key is undefined (not an error),\n"
        + "  // so `||` gives a sane default for a plain GUI \"Run\" click with no args supplied:\n"
        + "  // const host = args.HOST || 'pub400.com';\n"
        + "\n"
        + "  // --- Console output --- shows up live in the GUI's Scripts tab Console panel (and\n"
        + "  // on stderr from the CLI) as the script runs, not just after it finishes:\n"
        + "  // console.log('starting run');\n"
        + "  // console.error('something worth flagging - still lets the run continue');\n"
        + "\n"
        + "  // --- Saving a script's own data --- separate from the automatic per-run\n"
        + "  // results/extracted dump every run already gets; all three write under\n"
        + "  // extracted/scripts/<this script name>.<name below>.<json|csv>:\n"
        + "  // saveJson('summary', { host: host, ranAt: new Date().toISOString() }); // any JSON value, overwrites each call\n"
        + "  // saveCsv('observations', [{ row: 1, note: 'first' }]);                 // array of flat objects, overwrites each call\n"
        + "  // appendCsv('pages', [{ page: 1, job: 'QPADEV001' }]);                  // same shape, but ADDS instead of\n"
        + "  //   overwriting - call this once per page while paging through a multi-page subfile list\n"
        + "  //   (WRKACTJOB, WRKSPLF, ...) instead of the last page's saveCsv wiping out every earlier one.\n"
        + "} finally {\n"
        + "  disconnect();\n"
        + "}\n";

    private void handleScripts(HttpExchange ex) throws IOException {
        try {
            switch (ex.getRequestMethod()) {
                case "GET": {
                    File dir = scriptsDir();
                    dir.mkdirs();
                    List<Object> scripts = new ArrayList<>();
                    File[] found = dir.listFiles((d, n) -> n.endsWith(".js"));
                    if (found != null) {
                        java.util.Arrays.sort(found, java.util.Comparator.comparing(File::getName));
                        for (File f : found) {
                            String name = f.getName().substring(0, f.getName().length() - 3);
                            ScriptBatchFiles.writeIfMissing(dir, name); // self-heal a script that predates .bat/.sh generation
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", name);
                            scripts.add(m);
                        }
                    }
                    sendJson(ex, 200, Map.of("ok", true, "scripts", scripts));
                    return;
                }
                case "POST": {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    String name = safeName((String) req.get("name"));
                    File file = scriptFile(name);
                    if (file.exists()) {
                        sendJson(ex, 409, Map.of("ok", false, "error", "Script already exists: " + name));
                        return;
                    }
                    file.getParentFile().mkdirs();
                    java.nio.file.Files.writeString(file.toPath(), NEW_SCRIPT_TEMPLATE);
                    ScriptBatchFiles.write(file.getParentFile(), name);
                    sendJson(ex, 200, Map.of("ok", true, "name", name));
                    return;
                }
                case "DELETE": {
                    Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
                    String name = safeName(requireParam(query, "name"));
                    scriptFile(name).delete();
                    ScriptBatchFiles.delete(scriptsDir(), name);
                    sendJson(ex, 200, Map.of("ok", true));
                    return;
                }
                default:
                    sendJson(ex, 405, Map.of("ok", false, "error", "method not supported"));
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    /** /api/scripts/source : one script's own source - GET also returns the fixed
     * SCRIPT_DECLARATIONS text (for Monaco's addExtraLib) and every importSuite(flow, file) call
     * found in the source right now, parsed fresh on each GET - so the GUI's "declared imports"
     * display can never drift from what the script actually contains. */
    private static final java.util.regex.Pattern IMPORT_SUITE_CALL =
        java.util.regex.Pattern.compile("importSuite\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']+)[\"']\\s*\\)");

    private void handleScriptsSource(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String name = safeName(requireParam(query, "name"));
            File file = scriptFile(name);

            switch (ex.getRequestMethod()) {
                case "GET": {
                    boolean exists = file.exists();
                    String source = exists ? java.nio.file.Files.readString(file.toPath()) : "";
                    List<Object> imports = new ArrayList<>();
                    java.util.regex.Matcher m = IMPORT_SUITE_CALL.matcher(source);
                    while (m.find()) {
                        Map<String, Object> imp = new LinkedHashMap<>();
                        imp.put("flow", m.group(1));
                        imp.put("file", m.group(2));
                        imports.add(imp);
                    }
                    sendJson(ex, 200, Map.of("ok", true, "exists", exists, "source", source,
                        "declarations", SCRIPT_DECLARATIONS, "imports", imports));
                    return;
                }
                case "PUT": {
                    Map<String, Object> body = Json.parseObject(readBody(ex));
                    String source = String.valueOf(body.getOrDefault("source", ""));
                    file.getParentFile().mkdirs();
                    java.nio.file.Files.writeString(file.toPath(), source);
                    sendJson(ex, 200, Map.of("ok", true));
                    return;
                }
                default:
                    sendJson(ex, 405, Map.of("ok", false, "error", "GET or PUT only"));
            }
        } catch (Throwable e) {
            sendJson(ex, 400, SessionService.errorResponse(e));
        }
    }

    /** /api/scripts/run : runs one standalone script in the background, same runId/poll pattern as
     * /api/scenarios/run - reuses handleScenariosRunStatus (generic by runId) for polling and
     * writeRunArtifacts() under a "scripts" pseudo-flow bucket, so results/extracted/replays for a
     * script run are viewable through the exact same endpoints a suite run's are. No pre-connect
     * check here (unlike the old per-suite "connect" row's pseudo-case handling) - a script is
     * expected to call the connect() global itself (the new-script template always does); if it
     * doesn't and no session already exists, execute() surfaces a clear error the moment it's
     * actually needed rather than failing the whole run up front. */
    private void handleScriptsRun(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405, Map.of("ok", false, "error", "POST only"));
            return;
        }
        Map<String, Object> req = Json.parseObject(readBody(ex));
        String name = safeName((String) req.get("name"));
        String sessionId = req.getOrDefault("sessionId", "default").toString();
        boolean disconnectOnFinish = Boolean.TRUE.equals(req.get("disconnectOnFinish"));
        long stepDelayMs = req.get("stepDelayMs") instanceof Number
            ? ((Number) req.get("stepDelayMs")).longValue() : StepActions.DEFAULT_STEP_DELAY_MS;
        Map<String, String> scriptArgs = new LinkedHashMap<>();
        Object rawArgs = req.get("args");
        if (rawArgs instanceof Map) {
            ((Map<?, ?>) rawArgs).forEach((k, v) -> scriptArgs.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }

        try {
            ProjectRegistry.Project project = resolveProject(req);
            File scriptFile = new File(scriptsDir(project), name + ".js");
            if (!scriptFile.isFile()) {
                sendJson(ex, 200, Map.of("ok", false, "error", "No such script: " + name));
                return;
            }
            String jsSource = java.nio.file.Files.readString(scriptFile.toPath());
            File suitesRoot = new File(project.root, "suites");
            File extractedRoot = new File(project.root, "extracted");

            String runId = runTracker.start(1, sessionId, name, "script");
            RunTracker.RunState state = runTracker.get(runId);

            Thread runThread = new Thread(() -> {
                Progress.set(desc -> state.current = desc);
                try {
                    ScenarioResult r = JsSuiteRunner.run(sessionService, sessionId, name, suitesRoot, extractedRoot, jsSource, scriptArgs, state.console::add, stepDelayMs);
                    state.results.add(r.toMap());
                    state.current = "";
                    writeRunArtifacts(project, "scripts", name, List.of(r));
                    if (!state.cancelRequested) state.status = "done";
                } catch (Throwable e) {
                    if (!state.cancelRequested) {
                        state.status = "error";
                        state.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                } finally {
                    Progress.clear();
                    if (disconnectOnFinish) {
                        try { sessionService.disconnect(sessionId); } catch (Throwable ignored) {}
                    }
                }
            }, "script-run-" + runId);
            state.thread = runThread;
            runThread.start();

            sendJson(ex, 200, Map.of("ok", true, "runId", runId, "total", 1));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    private static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(data);
        return m;
    }

    private File flowDir(String flowName) {
        return flowDir(ProjectRegistry.current(), flowName);
    }

    private File flowDir(ProjectRegistry.Project project, String flowName) {
        return new File(new File(project.root, "suites"), safeName(flowName));
    }

    /** Where standalone scripts live - project/scripts/<name>.js, flat (no per-flow folder,
     * unlike suites/): a script isn't tied to any one flow's CSV schema, it can importSuite()
     * from any of them, so there's no flow to namespace it under. */
    private File scriptsDir() {
        return scriptsDir(ProjectRegistry.current());
    }

    private File scriptsDir(ProjectRegistry.Project project) {
        return new File(project.root, "scripts");
    }

    private File scriptFile(String name) {
        return new File(scriptsDir(), safeName(name) + ".js");
    }

    private File scenarioFile(String flowName, String fileName) {
        return new File(flowDir(flowName), fileName + ".csv");
    }

    private File scenarioFile(ProjectRegistry.Project project, String flowName, String fileName) {
        return new File(flowDir(project, flowName), fileName + ".csv");
    }

    private File varsFile(String flowName, String fileName) {
        return new File(flowDir(flowName), fileName + ".vars.csv");
    }

    private File varsFile(ProjectRegistry.Project project, String flowName, String fileName) {
        return new File(flowDir(project, flowName), fileName + ".vars.csv");
    }

    /** Resolves which project a run should use: an explicit "project" field in the request body
     * (Cli's `--project`, CI-friendly - pins a project for one call without depending on or
     * changing whatever the GUI currently has open), else whatever's currently active. */
    private ProjectRegistry.Project resolveProject(Map<String, Object> req) {
        Object p = req.get("project");
        if (p != null && !String.valueOf(p).isBlank()) return ProjectRegistry.byName(String.valueOf(p));
        return ProjectRegistry.current();
    }

    /** Strips path separators and traversal so file names can't escape the scenarios directory. */
    static String safeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String cleaned = name.trim().replaceAll("[\\\\/]", "_").replace("..", "_");
        if (cleaned.isBlank()) throw new IllegalArgumentException("invalid name");
        return cleaned;
    }

    private static String requireParam(Map<String, String> query, String key) {
        String v = query.get(key);
        if (v == null) throw new IllegalArgumentException("missing ?" + key + "=");
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> toStringRows(Object parsed) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Object o : (List<Object>) parsed) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                row.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
            }
            rows.add(row);
        }
        return rows;
    }

    // ---------- static files (the GUI itself) ----------

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        // no ".." traversal, no leading double slash tricks
        if (path.contains("..")) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        String resourcePath = "/web" + path;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("ok", false, "error", "not found: " + path));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    // ---------- helpers ----------

    private static String readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ex.getRequestBody().transferTo(buf);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        if (query == null) return m;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }
}
