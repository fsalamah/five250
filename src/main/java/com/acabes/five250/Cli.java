package com.acabes.five250;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin client: talks to the local Daemon over TCP, auto-starting it if needed. */
public final class Cli {

    public static void main(String[] args) throws Exception {
        // Running the jar plain (no command) or "serve" both just bring up the daemon + GUI and
        // stop there — for someone who wants to do everything (Connect included) from the
        // browser instead of the CLI, requiring a full "connect --host --port" first just to
        // get the portal open was a real, reported point of confusion.
        if (args.length == 0 || args[0].equals("serve")) {
            ensureDaemonRunning();
            System.out.println("GUI is up at http://127.0.0.1:" + HttpApi.PORT);
            if (args.length == 0) { System.out.println(); printUsage(); }
            return;
        }

        if (args[0].equals("daemon")) {
            Daemon.main(new String[0]);
            return;
        }

        if (args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            if (args.length > 1) HelpText.printFor(args[1]); else HelpText.printGeneral();
            return;
        }

        if (args[0].equals("run-suite")) {
            runSuite(args);
            return;
        }

        String cmd = args[0];
        Map<String, String> opts = parseOpts(args, 1);
        String session = opts.getOrDefault("session", "default");

        ensureDaemonRunning();

        Map<String, Object> req = new LinkedHashMap<>();
        switch (cmd) {
            case "connect":
                req.put("cmd", "connect");
                req.put("sessionId", session);
                req.put("host", require(opts, "host"));
                req.put("port", Long.parseLong(opts.getOrDefault("port", opts.containsKey("ssl") ? "992" : "23")));
                req.put("ssl", opts.containsKey("ssl"));
                break;
            case "signon":
                req.put("cmd", "signon");
                req.put("sessionId", session);
                req.put("user", require(opts, "user"));
                req.put("pass", require(opts, "pass"));
                break;
            case "screen":
                req.put("cmd", "screen");
                req.put("sessionId", session);
                break;
            case "fields":
                req.put("cmd", "fields");
                req.put("sessionId", session);
                break;
            case "type":
                req.put("cmd", "type");
                req.put("sessionId", session);
                if (opts.containsKey("label")) {
                    req.put("label", opts.get("label"));
                } else {
                    req.put("row", Long.parseLong(require(opts, "row")));
                    req.put("col", Long.parseLong(require(opts, "col")));
                }
                req.put("value", require(opts, "value"));
                break;
            case "key":
                req.put("cmd", "key");
                req.put("sessionId", session);
                req.put("key", opts.containsKey("name") ? opts.get("name") : positional(args));
                break;
            case "disconnect":
                req.put("cmd", "disconnect");
                req.put("sessionId", session);
                break;
            case "shutdown":
                req.put("cmd", "shutdown");
                break;
            default:
                System.err.println("Unknown command: " + cmd);
                printUsage();
                System.exit(1);
                return;
        }

        Map<String, Object> resp = send(req);
        boolean ok = Boolean.TRUE.equals(resp.get("ok"));
        if (!ok) {
            System.err.println("Error: " + resp.get("error"));
            System.exit(2);
            return;
        }

        if (opts.containsKey("json")) {
            System.out.println(Json.write(resp));
        } else {
            render(cmd, resp);
        }
    }

    // ---------- rendering ----------

    @SuppressWarnings("unchecked")
    private static void render(String cmd, Map<String, Object> resp) {
        if (cmd.equals("fields")) {
            renderFields((List<Object>) resp.get("fields"));
            return;
        }
        if (resp.containsKey("text")) {
            renderScreen(resp);
        } else {
            System.out.println(Json.write(resp));
        }
    }

    @SuppressWarnings("unchecked")
    private static void renderScreen(Map<String, Object> resp) {
        String text = (String) resp.get("text");
        long cols = (Long) resp.get("cols");
        long rows = (Long) resp.get("rows");
        for (int r = 0; r < rows; r++) {
            String line = text.substring((int) (r * cols), (int) (r * cols + cols));
            System.out.printf("%3d | %s%n", r + 1, line.stripTrailing());
        }
        Map<String, Object> cursor = (Map<String, Object>) resp.get("cursor");
        Map<String, Object> oia = (Map<String, Object>) resp.get("oia");
        System.out.printf("OIA: inhibited=%s locked=%s msgWait=%s  cursor=%s,%s%n",
            oia.get("inputInhibited"), oia.get("keyboardLocked"), oia.get("messageWait"),
            cursor.get("row"), cursor.get("col"));
    }

    @SuppressWarnings("unchecked")
    private static void renderFields(List<Object> fields) {
        System.out.printf("%-4s %-4s %-4s %-4s %-9s %-9s %s%n", "ROW", "COL", "LEN", "NUM", "PROTECTED", "", "VALUE");
        for (Object o : fields) {
            Map<String, Object> f = (Map<String, Object>) o;
            System.out.printf("%-4s %-4s %-4s %-4s %-9s          %s%n",
                f.get("row"), f.get("col"), f.get("length"), f.get("numeric"), f.get("protected"), f.get("value"));
        }
    }

    // ---------- wire protocol ----------

    private static Map<String, Object> send(Map<String, Object> req) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", Daemon.PORT), 5000);
            try (PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(Json.write(req));
                String line = in.readLine();
                if (line == null) throw new IOException("Daemon closed connection with no response");
                return Json.parseObject(line);
            }
        }
    }

    private static void ensureDaemonRunning() throws Exception {
        if (ping()) return;

        String jarPath = new File(Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        File logFile = new File(System.getProperty("java.io.tmpdir"), "five250-daemon.log");

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", jarPath, "daemon");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        pb.start();

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (ping()) return;
            Thread.sleep(150);
        }
        throw new IOException("Could not start five250 daemon (see " + logFile + ")");
    }

    private static boolean ping() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", Daemon.PORT), 300);
            try (PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                out.println("{\"cmd\":\"ping\"}");
                String line = in.readLine();
                return line != null && line.contains("\"ok\":true");
            }
        } catch (IOException e) {
            return false;
        }
    }

    // ---------- arg parsing ----------

    private static Map<String, String> parseOpts(String[] args, int from) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private static String positional(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) return args[i];
        }
        throw new IllegalArgumentException("expected a key name, e.g. `five250 key ENTER`");
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }

    // ---------- run-suite: drive a scenario file from outside the GUI, e.g. from CI ----------

    @SuppressWarnings("unchecked")
    private static void runSuite(String[] args) throws Exception {
        Map<String, String> opts = parseOpts(args, 1);
        Map<String, String> vars = parseVars(args);
        String flow = require(opts, "flow");
        String file = require(opts, "file");
        String session = opts.getOrDefault("session", "default");
        long timeoutSec = Long.parseLong(opts.getOrDefault("timeout", "300"));

        ensureDaemonRunning();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flow", flow);
        body.put("file", file);
        body.put("sessionId", session);
        if (!vars.isEmpty()) body.put("vars", vars);

        Map<String, Object> startJson = Json.parseObject(httpPost("/api/scenarios/run", Json.write(body)));
        if (!Boolean.TRUE.equals(startJson.get("ok"))) {
            System.err.println("Error: " + startJson.get("error"));
            System.exit(2);
            return;
        }
        String runId = (String) startJson.get("runId");
        long total = ((Number) startJson.get("total")).longValue();
        System.err.println("Running " + flow + "/" + file + " (" + total + " scenario" + (total == 1 ? "" : "s") + ")"
            + (vars.isEmpty() ? "" : " with vars " + vars) + " ...");

        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        Map<String, Object> status;
        String lastCurrent = "";
        while (true) {
            status = Json.parseObject(httpGet("/api/scenarios/run-status?runId=" + URLEncoder.encode(runId, StandardCharsets.UTF_8)));
            String current = String.valueOf(status.getOrDefault("current", ""));
            if (!current.isEmpty() && !current.equals(lastCurrent)) {
                System.err.println("  " + current);
                lastCurrent = current;
            }
            if (!"running".equals(status.get("status"))) break;
            if (System.currentTimeMillis() > deadline) {
                System.err.println("Timed out after " + timeoutSec + "s waiting for the run to finish");
                System.exit(3);
                return;
            }
            Thread.sleep(500);
        }

        if ("error".equals(status.get("status"))) {
            System.err.println("Run failed: " + status.get("error"));
            System.exit(2);
            return;
        }

        List<Object> results = (List<Object>) status.get("results");
        int passed = 0;
        for (Object o : results) {
            Map<String, Object> r = (Map<String, Object>) o;
            boolean ok = Boolean.TRUE.equals(r.get("passed"));
            if (ok) passed++;
            System.out.println((ok ? "PASS" : "FAIL") + "  " + r.get("row"));
            List<Object> checks = (List<Object>) r.get("checks");
            if (checks != null) {
                for (Object co : checks) {
                    Map<String, Object> c = (Map<String, Object>) co;
                    System.out.println("    " + c.get("name") + ": expected \"" + c.get("expected")
                        + "\" got \"" + c.get("actual") + "\" " + (Boolean.TRUE.equals(c.get("passed")) ? "OK" : "FAIL"));
                }
            }
            Map<String, Object> extracted = (Map<String, Object>) r.get("extracted");
            if (extracted != null && !extracted.isEmpty()) {
                for (Map.Entry<String, Object> e : extracted.entrySet()) {
                    if (e.getValue() instanceof java.util.List) {
                        java.util.List<?> rows = (java.util.List<?>) e.getValue();
                        System.out.println("    " + e.getKey() + " (" + rows.size() + " rows):");
                        for (Object row : rows) System.out.println("      " + row);
                    } else {
                        System.out.println("    " + e.getKey() + " = " + e.getValue());
                    }
                }
            }
            if (r.get("error") != null) System.out.println("    ERROR: " + r.get("error"));
        }
        System.out.println();
        System.out.println(passed + " / " + results.size() + " passed");
        System.exit(passed == results.size() ? 0 : 1);
    }

    private static Map<String, String> parseVars(String[] args) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--var") && i + 1 < args.length) {
                String kv = args[++i];
                int eq = kv.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("--var must be NAME=VALUE, got: " + kv);
                vars.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return vars;
    }

    private static String httpPost(String path, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + HttpApi.PORT + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
        return readAll(conn.getInputStream());
    }

    private static String httpGet(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + HttpApi.PORT + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        return readAll(conn.getInputStream());
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void printUsage() {
        HelpText.printGeneral();
    }
}
