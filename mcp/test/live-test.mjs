/**
 * Live end-to-end test of the five250 MCP server against pub400.com through a
 * real stdio MCP client (JSON-RPC over the spawned server's stdio).
 * Requires: five250 daemon reachable (or auto-startable) and PUB400_USER /
 * PUB400_PASS set in the environment. Passwords are never printed.
 * Exit 0 = all checks passed.
 */
import { McpClient } from "./client.mjs";

const USER = process.env.PUB400_USER || "";
const PASS = process.env.PUB400_PASS || "";
if (!USER || !PASS) {
  console.error("Set PUB400_USER and PUB400_PASS first.");
  process.exit(2);
}

const results = [];
let client;

const scrub = (s) => (PASS ? s.split(PASS).join("****") : s);
function record(id, ok, note = "") {
  results.push({ id, ok, note });
  console.log(`\n=== ${ok ? "PASS" : "FAIL"} ${id}${note ? " — " + scrub(note) : ""}`);
}
const excerpt = (t, n = 900) => scrub(t.length > n ? t.slice(0, n) + ` ...[${t.length} chars total]` : t);

async function expectOk(id, name, args, mustContain = []) {
  const r = await client.call(name, args);
  const missing = mustContain.filter((s) => !r.text.includes(s));
  const ok = !r.isError && missing.length === 0;
  record(id, ok, ok ? "" : r.isError ? "unexpected isError" : `missing: ${missing.join(" | ")}`);
  console.log(excerpt(r.text));
  return r;
}

async function expectErr(id, name, args, mustContain = []) {
  const r = await client.call(name, args);
  const missing = mustContain.filter((s) => !r.text.includes(s));
  const ok = r.isError && missing.length === 0;
  record(id, ok, ok ? "" : !r.isError ? "expected isError, got success" : `missing: ${missing.join(" | ")}`);
  console.log(excerpt(r.text, 400));
  return r;
}

/** Press ENTER past interstitial screens until a command line (===>) shows, max n times. */
async function ensureCommandLine(n = 3) {
  let r = await client.call("screen_read", {});
  for (let i = 0; i < n && !r.isError && !r.text.includes("===>"); i++) {
    r = await client.call("screen_press", { key: "ENTER" });
  }
  return r;
}

// simple CSV helpers for parsing suite_get/record_stop output back into rows
function parseCsvLine(line) {
  const out = [];
  let cur = "", q = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (q) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') q = false;
      else cur += ch;
    } else if (ch === '"') q = true;
    else if (ch === ",") { out.push(cur); cur = ""; }
    else cur += ch;
  }
  out.push(cur);
  return out;
}
function parseCsvBlock(text) {
  const lines = text.split("\n").filter((l) => l.includes(","));
  const start = lines.findIndex((l) => l.startsWith("case,"));
  if (start < 0) return null;
  const header = parseCsvLine(lines[start]);
  const rows = [];
  for (const l of lines.slice(start + 1)) {
    const vals = parseCsvLine(l);
    if (vals.length < 3) continue;
    rows.push(Object.fromEntries(header.map((h, i) => [h, vals[i] ?? ""])));
  }
  return rows;
}

const TS = Date.now().toString(36);
const SUITE = `mcp-live-${TS}`;
const SUITE2 = `mcp-rec-${TS}`;

try {
  client = new McpClient();

  // ---------- protocol level ----------
  const init = await client.initialize();
  record("P1 initialize", init.serverInfo?.name === "five250", JSON.stringify(init.serverInfo));
  const tools = await client.listTools();
  const names = tools.tools.map((t) => t.name).sort();
  console.log("tools:", names.join(", "));
  const expected = [
    "record_mark", "record_start", "record_status", "record_stop",
    "screen_extract", "screen_press", "screen_read", "screen_type",
    "session_connect", "session_disconnect", "session_list", "session_signon",
    "suite_get", "suite_list", "suite_run", "suite_run_status", "suite_save",
  ];
  record("P2 tools/list = expected 17", JSON.stringify(names) === JSON.stringify(expected), `${names.length} tools`);
  record("P3 every tool has schema+description", tools.tools.every((t) => t.inputSchema && t.description));
  try {
    const bad = await client.call("no_such_tool", {});
    record("P4 unknown tool rejected", bad.isError && /no_such_tool|not found/i.test(bad.text), bad.text.slice(0, 90));
  } catch (e) {
    record("P4 unknown tool rejected (JSON-RPC error)", true, e.message.slice(0, 90));
  }

  // ---------- negative: nothing connected ----------
  await expectErr("N1 screen_read before connect", "screen_read", {}, ["No session", "session_connect"]);
  await expectErr("N2 press before connect", "screen_press", { key: "ENTER" }, ["No session"]);
  await expectErr("N3 bad key name", "screen_press", { key: "F99" }, ["Valid AID keys"]);
  await expectErr("N4 mark without recording", "record_mark", { kind: "check", target: "message", expected: "x" }, ["record_start"]);
  await expectErr("N5 run nonexistent suite", "suite_run", { flow: "custom-steps", file: "definitely-not-real" }, ["No suite"]);
  await expectErr("N6 bad flow", "suite_list", { flow: "nope" }, ["No such flow"]);
  await expectErr("N7 signon without session", "session_signon", {}, ["No session"]);
  await expectErr("N8 connect bad host", "session_connect", { host: "no-such-host.invalid", port: 23 }, []);
  await expectErr("N8b type missing args", "screen_type", { value: "X" }, ["label", "row"]);

  // ---------- phase A: live exploration (unrecorded) ----------
  await expectOk("A1 suite_list", "suite_list", { flow: "custom-steps" }, ['flow "custom-steps"']);
  await expectOk("A2 connect pub400", "session_connect", { host: "pub400.com", port: 23 }, ["screen 24x80", "input fields"]);
  await expectOk("A3 reconnect is idempotent", "session_connect", { host: "pub400.com", port: 23 }, ["already connected"]);
  await expectOk("A4 session_list", "session_list", {}, ["default: connected"]);
  await expectOk("A5 screen_read", "screen_read", {}, ["cursor", "message line", "input fields"]);

  // negative on the live screen
  await expectErr("N9 type at protected position", "screen_type", { value: "X", row: 1, col: 2 }, ["No input field at row"]);
  await expectErr("N10 type at bad label", "screen_type", { value: "X", label: "Nonexistent Label XYZ" }, ["No input field found near labels"]);
  await expectErr("N11 extract bad target", "screen_extract", { target: "bogus:thing" }, ["Invalid extract target"]);
  await expectErr("N12 extract row out of range", "screen_extract", { target: "row:99" }, ["out of range"]);
  await expectErr("N13 extract label missing", "screen_extract", { target: "label:Flux Capacitor" }, ["Label not found"]);

  await expectOk("A6 signon via env credentials", "session_signon", {}, ["screen 24x80"]);
  const menu = await ensureCommandLine();
  record("A7 reached a command line", menu.text.includes("===>"), menu.text.includes("===>") ? "" : excerpt(menu.text, 300));
  await expectOk("A8 WRKACTJOB via label ===> + ENTER", "screen_type", { value: "WRKACTJOB", label: "===>", then_press: "ENTER" }, ["Pressed ENTER"]);
  const title = await expectOk("A9 title row extract", "screen_extract", { target: "row:1" }, ["extracted value"]);
  record("A9b title is WRKACTJOB", /Active Jobs/i.test(title.text), title.text.slice(0, 120));
  await expectOk("A10 extract protected header value", "screen_extract", { target: "label:Active jobs:" }, ["extracted value"]);
  await expectOk("A11 extract subfile rows", "screen_extract", { target: "rows:9-14:1-60" }, ["extracted 6 line(s)"]);
  await expectOk("A12 F3 back", "screen_press", { key: "F3" }, ["Pressed F3", "Main Menu"]);
  // sign off to land back on the entry screen for the recording phase (tolerant: some hosts drop the line)
  const sof = await client.call("screen_type", { value: "SIGNOFF", label: "===>", then_press: "ENTER" });
  record("A13 SIGNOFF submitted", true, sof.isError ? `connection dropped (ok): ${sof.text.slice(0, 90)}` : "returned to entry screen");

  // ---------- phase B: record a suite like a professional would ----------
  await expectOk("B1 record_start", "record_start", { case_name: "signon-nav" }, ["Recording started"]);
  await expectOk("B2 record_status active", "record_status", {}, ["ACTIVE"]);

  let scr = await client.call("screen_read", {});
  if (scr.isError || !/user/i.test(scr.text)) {
    // connection dropped at signoff — reconnect WHILE recording so the connect step is captured
    await client.call("session_disconnect", {}).catch(() => {});
    await client.call("record_start", { case_name: "signon-nav" }); // restart clean (disconnect may have recorded)
    scr = await client.call("session_connect", { host: "pub400.com", port: 23 });
    record("B3 reconnected while recording", !scr.isError);
  } else {
    record("B3 still on entry screen", true);
  }
  const userLabel = scr.text.includes("Your user name") ? "Your user name" : "User";
  const passLabel = scr.text.includes("Password (max. 128)") ? "Password (max. 128)" : "Password";
  await expectOk("B4 type user", "screen_type", { value: USER, label: userLabel }, ["Typed into"]);
  await expectOk("B5 type password + ENTER", "screen_type", { value: PASS, label: passLabel, then_press: "ENTER" }, ["Pressed ENTER"]);
  const menu2 = await ensureCommandLine();
  record("B6 signed on to a command line", menu2.text.includes("===>"));
  await expectOk("B7 WRKACTJOB", "screen_type", { value: "WRKACTJOB", label: "===>", then_press: "ENTER" }, ["Pressed ENTER"]);
  await expectOk("B8 mark check on title", "record_mark", { kind: "check", target: "row:1", expected: "Active Jobs" }, ["Recorded check step"]);
  await expectOk("B9 mark extract active_jobs", "record_mark", { kind: "extract", target: "label:Active jobs:", name: "active_jobs" }, ["Recorded extract step"]);
  await expectOk("B10 mark extract job rows", "record_mark", { kind: "extract", target: "rows:9-14:1-60", name: "job_rows" }, ["Recorded extract step"]);
  await expectOk("B11 F3 back to menu", "screen_press", { key: "F3" }, ["Pressed F3"]);
  await expectOk("B12 disconnect (recorded)", "session_disconnect", {});
  const stopped = await expectOk("B13 record_stop -> rows returned, not saved", "record_stop", {}, ["NOT saved"]);

  // ---------- phase C: parameterize + save + run ----------
  let rows = parseCsvBlock(stopped.text);
  record("C1 parsed recorded rows", Array.isArray(rows) && rows.length >= 6, `rows=${rows?.length}`);
  if (!rows.some((r) => r.action === "connect")) {
    rows.unshift({ case: "setup", step: "1", action: "connect", target: "pub400.com", value: "23", expected: "" });
  }
  for (const r of rows) {
    if (r.value === USER) r.value = "${USER}";
    if (r.value === PASS) r.value = "${PASSWORD}";
  }
  record("C2 no literal password in rows", !rows.some((r) => Object.values(r).some((v) => v.includes(PASS))));

  await expectOk("C3 suite_save new suite + placeholder vars", "suite_save",
    { flow: "custom-steps", file: SUITE, rows, vars: { USER: "your-pub400-user", PASSWORD: "your-pub400-password" } },
    ["Saved"]);
  await expectErr("N14 suite_save again without overwrite", "suite_save", { flow: "custom-steps", file: SUITE, rows }, ["overwrite=true"]);
  await expectOk("C4 suite_save with overwrite", "suite_save", { flow: "custom-steps", file: SUITE, rows, overwrite: true }, ["Saved"]);
  const got = await expectOk("C5 suite_get shows placeholders", "suite_get", { flow: "custom-steps", file: SUITE },
    ["${USER}", "${PASSWORD}", "your-pub400-user"]);
  record("C6 suite_get contains no real password", !got.text.includes("****") && !got.text.includes(PASS));

  const run = await expectOk("C7 suite_run from cold with real-var overrides", "suite_run",
    { flow: "custom-steps", file: SUITE, vars: { USER, PASSWORD: PASS }, wait_seconds: 240 },
    ["status: done"]);
  record("C8 scenario PASS + active_jobs extracted", /\[PASS\]/.test(run.text) && /extracted active_jobs:\s*\S+/.test(run.text),
    (run.text.match(/extracted active_jobs:.*/) || ["no extract line"])[0]);
  record("C9 job rows extracted as list", /extracted job_rows:/.test(run.text));
  record("C10 run output has no password", !run.text.includes(PASS));

  // negative around running
  await expectErr("N15 connect-less suite, no session", "suite_run", { flow: "custom-steps", file: "signon-common" }, ["not connected"]);
  await expectErr("N16 bogus run id", "suite_run_status", { run_id: "not-a-run" }, []);

  // ---------- phase D: record_stop direct-save path + discard path ----------
  await expectOk("D1 record_start", "record_start", { case_name: "mini" }, ["Recording started"]);
  await expectOk("D2 connect while recording", "session_connect", { host: "pub400.com", port: 23 }, ["Connected"]);
  await expectOk("D3 a key press", "screen_press", { key: "ENTER" }, ["Pressed ENTER"]);
  await expectOk("D4 record_stop saves directly", "record_stop", { flow: "custom-steps", file: SUITE2 }, ["saved as suite"]);
  await expectOk("D5 saved suite listed", "suite_list", { flow: "custom-steps" }, [SUITE2]);
  await expectOk("D6 record_start again", "record_start", {}, ["Recording started"]);
  await expectOk("D7 record_stop discard", "record_stop", { discard: true }, ["discarded"]);
  await expectOk("D8 record_status inactive", "record_status", {}, ["Not recording"]);
  await expectOk("D9 disconnect", "session_disconnect", {});
  await expectErr("N17 record_stop with flow only", "record_stop", { flow: "custom-steps" }, ["both flow and file"]);
} catch (e) {
  record("UNEXPECTED", false, e.stack || String(e));
} finally {
  client?.close();
}

console.log("\n\n========== SUMMARY ==========");
let fails = 0;
for (const r of results) {
  if (!r.ok) fails++;
  console.log(`${r.ok ? "PASS" : "FAIL"}  ${r.id}${r.note ? " — " + scrub(r.note) : ""}`);
}
console.log(`${results.length - fails}/${results.length} passed`);
process.exit(fails ? 1 : 0);
