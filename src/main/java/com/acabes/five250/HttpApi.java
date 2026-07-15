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

    private static final File SCENARIOS_DIR = Home.file("scenarios");
    private static final File FAILURES_DIR = Home.file("docs/samples/failures");
    private static final File REPLAYS_DIR = Home.file("docs/samples/replays");

    private final SessionService sessionService;
    private final RunTracker runTracker = new RunTracker();

    public HttpApi(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/rpc", this::handleRpc);
        server.createContext("/api/flows", this::handleFlows);
        server.createContext("/api/scenario-files", this::handleScenarioFiles);
        server.createContext("/api/scenarios/run-status", this::handleScenariosRunStatus);
        server.createContext("/api/scenarios/run", this::handleScenariosRun);
        server.createContext("/api/scenarios/replay", this::handleScenarioReplay);
        server.createContext("/api/scenario-vars", this::handleScenarioVars);
        server.createContext("/api/scenarios", this::handleScenarios);
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
                File[] found = dir.listFiles((d, n) -> n.endsWith(".csv") && !n.endsWith(".results.csv") && !n.endsWith(".vars.csv"));
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

        try {
            Flow flow = FlowRegistry.get(flowName);
            File csvFile = scenarioFile(flowName, fileName);
            List<Map<String, String>> rows = Csv.read(csvFile);
            rows = flow.preprocess(flowDir(flowName), rows); // splice in any "include" steps
            Map<String, String> vars = new LinkedHashMap<>(Variables.load(varsFile(flowName, fileName)));
            Object varsOverride = req.get("vars"); // CLI/API caller can supply/override ${NAME} values externally
            if (varsOverride instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) varsOverride).entrySet()) {
                    vars.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            rows = Variables.substituteRows(rows, vars); // resolve ${NAME} in every cell

            rows = autoConnectIfNeeded(sessionId, rows); // suite can create its own session via a "connect" step
            Terminal t = sessionService.getSession(sessionId); // now guaranteed to exist
            final List<Map<String, String>> finalRows = rows;
            int total = ScenarioRunner.countScenarios(flow, finalRows);

            String runId = runTracker.start(total);
            RunTracker.RunState state = runTracker.get(runId);

            new Thread(() -> {
                Progress.set(desc -> state.current = desc);
                try {
                    List<ScenarioResult> results = ScenarioRunner.run(flow, t, finalRows,
                        r -> { state.results.add(r.toMap()); state.current = ""; });
                    ScenarioRunner.writeResults(new File(flowDir(flowName), fileName + ".results.csv"), results);
                    ScenarioRunner.writeFailureDumps(new File(new File(FAILURES_DIR, flowName), fileName), results);
                    ScenarioRunner.writeReplays(new File(new File(REPLAYS_DIR, flowName), fileName), results);
                    state.status = "done";
                } catch (Throwable e) {
                    state.status = "error";
                    state.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                } finally {
                    Progress.clear();
                }
            }, "scenario-run-" + runId).start();

            sendJson(ex, 200, Map.of("ok", true, "runId", runId, "total", total));
        } catch (Throwable e) {
            sendJson(ex, 200, SessionService.errorResponse(e));
        }
    }

    /**
     * If a suite has a row with action="connect" (custom-steps: target=host, value=port,
     * expected="true" for SSL), and the session doesn't already exist, connects it — so a
     * suite can create its own session from scratch, no prior manual Connect click needed.
     * Either way, strips every row belonging to that row's case, so it never reaches a Flow
     * as an unrecognized action.
     */
    private List<Map<String, String>> autoConnectIfNeeded(String sessionId, List<Map<String, String>> rows) {
        Map<String, String> connectRow = null;
        for (Map<String, String> row : rows) {
            if ("connect".equalsIgnoreCase(row.getOrDefault("action", "").trim())) {
                connectRow = row;
                break;
            }
        }
        if (connectRow == null) return rows;

        boolean alreadyConnected;
        try {
            sessionService.getSession(sessionId);
            alreadyConnected = true;
        } catch (RuntimeException e) {
            alreadyConnected = false;
        }

        if (!alreadyConnected) {
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

    /** Fetches one persisted replay (step-by-step screen captures) written after a run finished. */
    private void handleScenarioReplay(HttpExchange ex) throws IOException {
        try {
            Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
            String flowName = requireParam(query, "flow");
            String fileName = safeName(requireParam(query, "file"));
            int index = Integer.parseInt(requireParam(query, "index"));
            File f = new File(new File(new File(REPLAYS_DIR, flowName), fileName), "row-" + index + ".json");
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

    private static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(data);
        return m;
    }

    private File flowDir(String flowName) {
        return new File(SCENARIOS_DIR, safeName(flowName));
    }

    private File scenarioFile(String flowName, String fileName) {
        return new File(flowDir(flowName), fileName + ".csv");
    }

    private File varsFile(String flowName, String fileName) {
        return new File(flowDir(flowName), fileName + ".vars.csv");
    }

    /** Strips path separators and traversal so file names can't escape the scenarios directory. */
    private static String safeName(String name) {
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
