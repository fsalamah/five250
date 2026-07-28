#!/usr/bin/env node
/**
 * five250 MCP server — stdio transport, thin wrapper over the five250 daemon's
 * HTTP API (http://127.0.0.1:25251). All state (sessions, recordings, suites)
 * lives in the daemon; this process is stateless glue and can restart freely.
 *
 * Design record: ./DESIGN.md   Usage: ./README.md
 * IMPORTANT: stdout is the JSON-RPC channel — log only via console.error.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const BASE = process.env.FIVE250_URL || "http://127.0.0.1:25251";
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const JAR = process.env.FIVE250_JAR || path.join(REPO, "target", "five250.jar");
const HOME = process.env.FIVE250_HOME || REPO;

const VALID_KEYS =
  "ENTER, F1-F24 (or PF1-PF24), PA1-PA3, PAGE_UP (PGUP), PAGE_DOWN (PGDN), TAB, " +
  "BACK_TAB, HOME, CLEAR, HELP, SYSREQ, RESET, ATTN, ERASE_EOF, ERASE_FIELD, BACK_SPACE";
const KEY_RE =
  /^(ENTER|P?F([1-9]|1[0-9]|2[0-4])|PA[1-3]|PAGE_UP|PGUP|PAGEUP|PAGE_DOWN|PGDN|PAGEDOWN|TAB|BACK_TAB|BACKTAB|HOME|CLEAR|HELP|SYSREQ|RESET|ATTN|ERASE_EOF|ERASEEOF|ERASE_FIELD|ERASEFIELD|BACK_SPACE|BACKSPACE)$/;

// ---------------------------------------------------------------- daemon I/O

let daemonStartAttempted = false;

async function httpJson(method, urlPath, body) {
  const doFetch = async () => {
    const res = await fetch(BASE + urlPath, {
      method,
      headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return res.json();
  };
  try {
    return await doFetch();
  } catch (e) {
    if (!isConnRefused(e)) throw e;
    await tryStartDaemon();
    try {
      return await doFetch();
    } catch (e2) {
      if (isConnRefused(e2)) {
        throw new Error(
          `five250 daemon is not reachable at ${BASE} and auto-start failed. ` +
            `Start it manually: FIVE250_HOME="${HOME}" java -jar "${JAR}" serve`
        );
      }
      throw e2;
    }
  }
}

function isConnRefused(e) {
  const s = String(e?.cause?.code || e?.code || e?.message || e);
  return /ECONNREFUSED|ECONNRESET|fetch failed|UND_ERR/i.test(s);
}

async function tryStartDaemon() {
  if (daemonStartAttempted) return;
  daemonStartAttempted = true;
  console.error(`[five250-mcp] daemon not reachable, starting: java -jar ${JAR} serve (FIVE250_HOME=${HOME})`);
  try {
    const child = spawn("java", ["-jar", JAR, "serve"], {
      detached: true,
      stdio: "ignore",
      env: { ...process.env, FIVE250_HOME: HOME },
    });
    child.unref();
  } catch (e) {
    console.error(`[five250-mcp] spawn failed: ${e.message}`);
    return;
  }
  for (let i = 0; i < 20; i++) {
    await new Promise((r) => setTimeout(r, 500));
    try {
      const res = await fetch(BASE + "/api/flows");
      if (res.ok) {
        console.error("[five250-mcp] daemon is up");
        return;
      }
    } catch {}
  }
  console.error("[five250-mcp] daemon did not come up within 10s");
}

/** POST /api/rpc; throws Error with an actionable message on ok:false. */
async function rpc(req) {
  const resp = await httpJson("POST", "/api/rpc", req);
  if (resp.ok !== true) throw new Error(improveError(resp.error || "unknown daemon error"));
  return resp;
}

function improveError(msg) {
  let out = String(msg);
  if (/^No session '/.test(out)) out += " Use session_connect (then session_signon) before screen/record tools.";
  if (/is not connected$/.test(out)) out += " The connection dropped or was disconnected — session_connect again.";
  if (/^Unknown key/.test(out)) out = `${out}. Valid AID keys: ${VALID_KEYS}.`;
  if (/^No input field at row/.test(out))
    out += " Run screen_read and pick coordinates from the fields table (or use a label: target).";
  if (/^No input field found near labels/.test(out))
    out += " Run screen_read; the label text must appear on the current screen exactly (case-insensitive), with an unprotected field after it.";
  if (/^Not recording/.test(out)) out += " Use record_start first, or check record_status.";
  return out;
}

// ------------------------------------------------------------- screen render

function renderScreen(snap, note) {
  const cols = Number(snap.cols || 80);
  const rows = Number(snap.rows || 24);
  const text = snap.text || "";
  const lines = [];
  if (note) lines.push(note);
  const oia = snap.oia || {};
  const locked = oia.keyboardLocked || oia.inputInhibited;
  lines.push(
    `screen ${rows}x${cols} | cursor ${snap.cursor?.row},${snap.cursor?.col} | ` +
      `keyboard ${locked ? "LOCKED (do not type — if this persists, the host is busy)" : "ready"}` +
      (oia.messageWait ? " | MESSAGE WAITING" : "")
  );
  let ruler = "";
  for (let c = 1; c <= cols; c++) ruler += c % 10 === 0 ? String((c / 10) % 10) : c % 5 === 0 ? "+" : ".";
  lines.push("   " + ruler);
  for (let r = 0; r < rows; r++) {
    const row = text.substring(r * cols, (r + 1) * cols).replace(/\s+$/, "");
    lines.push(String(r + 1).padStart(2) + "|" + row);
  }
  const msg = lastRowTrimmed(snap);
  lines.push(`message line (row ${rows}): ${msg === "" ? "(empty)" : JSON.stringify(msg)}`);
  const fields = snap.fields || [];
  const editable = fields.filter((f) => !f.protected);
  if (editable.length === 0) {
    lines.push("input fields: none (display-only screen — press an AID key to continue)");
  } else {
    lines.push(`input fields (${editable.length}) — use with screen_type:`);
    for (const f of editable) {
      const near = inferNearLabel(text, cols, Number(f.row), Number(f.col));
      lines.push(
        `  row ${String(f.row).padStart(2)} col ${String(f.col).padStart(2)} len ${String(f.length).padStart(3)}` +
          `${f.numeric ? " numeric" : ""} value=${JSON.stringify(f.value ?? "")}` +
          (near ? ` label≈${JSON.stringify(near)}` : "")
      );
    }
  }
  return lines.join("\n");
}

function lastRowTrimmed(snap) {
  const cols = Number(snap.cols || 80);
  const rows = Number(snap.rows || 24);
  return (snap.text || "").substring((rows - 1) * cols, rows * cols).trim();
}

/** Text on the same row to the left of the field — a hint, not authoritative. */
function inferNearLabel(text, cols, row, col) {
  const rowText = text.substring((row - 1) * cols, (row - 1) * cols + (col - 1));
  const m = rowText.trimEnd();
  if (!m) return "";
  const parts = m.split(/ {2,}/);
  return parts[parts.length - 1].trim().slice(0, 40);
}

// ------------------------------------------- extract grammar (same as CSV `extract`)

function extractFromSnapshot(snap, target) {
  const cols = Number(snap.cols || 80);
  const rows = Number(snap.rows || 24);
  const text = snap.text || "";
  const rowText = (r, c1 = 1, c2 = cols) => {
    if (r < 1 || r > rows) throw new Error(`row ${r} out of range 1-${rows}`);
    const start = (r - 1) * cols + Math.max(0, c1 - 1);
    const end = (r - 1) * cols + Math.min(c2, cols);
    return start >= end ? "" : text.substring(start, end).replace(/\s+$/, "");
  };
  const t = target.trim();
  if (t === "message") return { value: lastRowTrimmed(snap) };
  if (/^row:\d+$/.test(t)) return { value: rowText(Number(t.slice(4))).trim() };
  let m = t.match(/^rows:(\d+)-(\d+)(?::(\d+)-(\d+))?$/);
  if (m) {
    const [a, b] = [Number(m[1]), Number(m[2])];
    if (a > b) throw new Error(`rows range start ${a} > end ${b}`);
    const [c1, c2] = m[3] ? [Number(m[3]), Number(m[4])] : [1, cols];
    const out = [];
    for (let r = a; r <= b; r++) out.push(rowText(r, c1, c2));
    return { values: out };
  }
  if (t.startsWith("label:")) {
    const label = t.slice(6);
    let idx = text.indexOf(label);
    if (idx < 0) idx = text.toUpperCase().indexOf(label.toUpperCase());
    if (idx < 0) throw new Error(`Label not found on screen: ${label}. Run screen_read to see the actual text.`);
    const labelEnd = idx + label.length;
    const row = Math.floor(labelEnd / cols);
    const rest = text.substring(labelEnd, Math.min((row + 1) * cols, text.length)).replace(/^ +/, "");
    return { value: rest.split(/ {2,}/, 2)[0]?.trim() ?? "" };
  }
  throw new Error(
    `Invalid extract target ${JSON.stringify(target)}. Use "message", "row:<n>", ` +
      `"rows:<start>-<end>[:<colStart>-<colEnd>]", or "label:<text>".`
  );
}

// ------------------------------------------------------------------- helpers

const sid = z.string().default("default").describe("Session id (default: \"default\"). The GUI and CLI share these ids.");

function textResult(s) {
  return { content: [{ type: "text", text: s }] };
}

function errResult(s) {
  return { content: [{ type: "text", text: `ERROR: ${s}` }], isError: true };
}

/** Wrap a handler so any thrown Error becomes an isError tool result. */
function guard(fn) {
  return async (args) => {
    try {
      return await fn(args ?? {});
    } catch (e) {
      return errResult(e?.message || String(e));
    }
  };
}

function normalizeKey(raw) {
  const k = String(raw).trim().toUpperCase().replace(/[- ]/g, "_");
  if (!KEY_RE.test(k)) throw new Error(`Unknown key "${raw}". Valid AID keys: ${VALID_KEYS}.`);
  return k;
}

function rowsToCsvText(columns, rows) {
  const esc = (v) => {
    const s = String(v ?? "");
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [columns.join(",")];
  for (const r of rows) lines.push(columns.map((c) => esc(r[c])).join(","));
  return lines.join("\n");
}

async function flowColumns(flowName) {
  const resp = await httpJson("GET", "/api/flows");
  const flow = (resp.flows || []).find((f) => f.name === flowName);
  if (!flow) {
    const names = (resp.flows || []).map((f) => f.name).join(", ");
    throw new Error(`No such flow "${flowName}". Available flows: ${names}.`);
  }
  return flow.columns;
}

async function saveSuite(flowName, fileName, rows) {
  const columns = await flowColumns(flowName);
  // normalize: keep only known columns, fill missing with ""
  const clean = rows.map((r) => Object.fromEntries(columns.map((c) => [c, String(r[c] ?? "")])));
  const put = await httpJson("PUT", `/api/scenarios?flow=${encodeURIComponent(flowName)}&file=${encodeURIComponent(fileName)}`, clean);
  if (put.ok !== true) throw new Error(String(put.error || "save failed"));
  return clean.length;
}

async function suiteExists(flowName, fileName) {
  const resp = await httpJson("GET", `/api/scenario-files?flow=${encodeURIComponent(flowName)}`);
  if (resp.ok !== true) throw new Error(String(resp.error));
  return (resp.files || []).some((f) => f.name === fileName);
}

async function saveVars(flowName, fileName, vars) {
  const rows = Object.entries(vars).map(([name, value]) => ({ name, value: String(value) }));
  const put = await httpJson(
    "PUT",
    `/api/scenario-vars?flow=${encodeURIComponent(flowName)}&file=${encodeURIComponent(fileName)}`,
    rows
  );
  if (put.ok !== true) throw new Error(String(put.error || "vars save failed"));
}

function summarizeResults(state) {
  const lines = [];
  const results = state.results || [];
  let pass = 0,
    fail = 0;
  for (const r of results) {
    const ok = r.passed === true;
    ok ? pass++ : fail++;
    const name = r.row?.case ?? r.row?.command ?? r.name ?? "scenario";
    let line = `  [${ok ? "PASS" : "FAIL"}] ${name}`;
    if (!ok && r.error) line += ` — ${r.error}`;
    lines.push(line);
    for (const c of r.checks || []) {
      if (c.passed === false || (!ok && c.actual !== undefined))
        lines.push(`    check ${c.name}: expected contains ${JSON.stringify(c.expected)}, actual ${JSON.stringify(c.actual)}`);
    }
    const ex = r.extracted;
    if (ex && typeof ex === "object" && Object.keys(ex).length) {
      for (const [k, v] of Object.entries(ex)) {
        const vs = Array.isArray(v) ? v.map((x) => `\n      ${x}`).join("") : ` ${v}`;
        lines.push(`    extracted ${k}:${vs}`);
      }
    }
  }
  lines.unshift(
    `status: ${state.status} | scenarios: ${results.length} (${pass} pass, ${fail} fail)` +
      (state.error ? ` | run error: ${state.error}` : "")
  );
  lines.push("Full artifacts on disk: results/<flow>/<file>.results.csv, extracted/<flow>/, replays under docs/samples/replays/.");
  return lines.join("\n");
}

// --------------------------------------------------------------------- server

const server = new McpServer(
  { name: "five250", version: "1.0.0" },
  {
    instructions: `five250 drives a REAL, live IBM i (AS/400) 5250 terminal session and its suite engine.

Core model — 5250 is BLOCK MODE: you fill input fields, press ONE AID key (ENTER, F1-F24, PAGE_DOWN...), and a whole new screen arrives. There are no events or partial updates. Every action tool returns the resulting screen; ALWAYS read it before the next action. Never guess labels, coordinates, or screen contents — observe first (screen_read), act second. Errors from the host appear on the row-24 message line, which every render shows.

Professional workflow this server mirrors:
1. navigate/observe: session_connect → session_signon → screen_read / screen_type / screen_press, reading each returned screen.
2. record: record_start, then perform the flow live; mark assertions (record_mark check) and data pulls (record_mark extract) at the right screens; record_stop saves a runnable CSV suite.
3. parameterize: suite_get / suite_save — replace literals with \${NAME} placeholders and matching vars. NEVER save literal passwords in a suite; use \${PASSWORD} + a placeholder vars value.
4. test: suite_run (re-run from a cold state to prove it), inspect per-scenario PASS/FAIL and extracted data.

Targeting: prefer label:<exact text> (survives layout changes) over row/col. Field coordinates come from the fields table in every screen render. Gotcha: a suite that "include"s another does NOT inherit its vars — redeclare them.`,
  }
);

// ---- session tools

server.tool(
  "session_connect",
  "Connect a new 5250 terminal session to an IBM i host and return the first screen. For pub400.com use port 23 plain (SSL/992 hangs in this build). If the session id already exists and is live, this reports it instead of reconnecting.",
  {
    host: z.string().describe('Host name, e.g. "pub400.com"'),
    port: z.number().int().default(23).describe("TCP port (default 23, plain telnet)"),
    ssl: z.boolean().default(false).describe("TLS connect — known broken against pub400, leave false"),
    session_id: sid,
  },
  guard(async ({ host, port, ssl, session_id }) => {
    const sessions = await rpc({ cmd: "sessions" });
    if (sessions.sessions?.[session_id] === "connected") {
      return textResult(
        `Session "${session_id}" is already connected — not reconnecting. Use screen_read to see it, or session_disconnect first.`
      );
    }
    const resp = await rpc({ cmd: "connect", sessionId: session_id, host, port, ssl });
    return textResult(renderScreen(resp, `Connected "${session_id}" to ${host}:${port}.`));
  })
);

server.tool(
  "session_signon",
  "Sign on at the current sign-on screen. By default credentials are read from environment variables (PUB400_USER / PUB400_PASS) inside the MCP server — preferred, so no password enters the conversation. Pass literal user/pass only for hosts without env vars configured. The password is never echoed back. If the returned screen still shows sign-on fields or a row-24 message, the sign-on failed — read it.",
  {
    user: z.string().optional().describe("Literal user profile (prefer user_env)"),
    pass: z.string().optional().describe("Literal password (prefer pass_env; never store literals in suites)"),
    user_env: z.string().default("PUB400_USER").describe("Env var holding the user profile"),
    pass_env: z.string().default("PUB400_PASS").describe("Env var holding the password"),
    session_id: sid,
  },
  guard(async ({ user, pass, user_env, pass_env, session_id }) => {
    const u = user ?? process.env[user_env];
    const p = pass ?? process.env[pass_env];
    if (!u || !p)
      throw new Error(
        `No credentials: pass user/pass explicitly or set env vars ${user_env}/${pass_env} in the MCP server's environment.`
      );
    const resp = await rpc({ cmd: "signon", sessionId: session_id, user: u, pass: p });
    return textResult(renderScreen(resp, `Sign-on submitted for user "${u}".`));
  })
);

server.tool(
  "session_disconnect",
  "Close a session's connection and remove it from the daemon's registry. If recording, a 'disconnect' step is recorded so replays reach the same end state.",
  { session_id: sid },
  guard(async ({ session_id }) => {
    await rpc({ cmd: "disconnect", sessionId: session_id });
    return textResult(`Session "${session_id}" disconnected.`);
  })
);

server.tool(
  "session_list",
  "List all sessions in the daemon and whether each is actually connected. The browser GUI and CLI share these same sessions.",
  {},
  guard(async () => {
    const resp = await rpc({ cmd: "sessions" });
    const entries = Object.entries(resp.sessions || {});
    if (!entries.length) return textResult("No sessions. Use session_connect to open one.");
    return textResult(entries.map(([id, st]) => `${id}: ${st}`).join("\n"));
  })
);

// ---- screen tools

server.tool(
  "screen_read",
  "Read the current screen of a live session: numbered rows with a column ruler, cursor position, keyboard/OIA state, the row-24 message line, and a table of editable input fields (row/col/length/current value/nearby label). Call this whenever you are unsure what is on screen — it performs no action.",
  { session_id: sid },
  guard(async ({ session_id }) => {
    const resp = await rpc({ cmd: "screen", sessionId: session_id });
    return textResult(renderScreen(resp));
  })
);

server.tool(
  "screen_type",
  'Put a value into ONE editable field, addressed by label (preferred — exact visible text left of the field, e.g. "User" or "Selection or command") or by row+col from the fields table. Typing alone does NOT submit: pass then_press (usually "ENTER") to submit in the same call, or call screen_press separately after filling multiple fields. If the type fails, no key is pressed. While recording, this records a type step (and a key step if then_press is set).',
  {
    value: z.string().describe("Text to put in the field (numeric fields: digits only)"),
    label: z.string().optional().describe("Visible label text anchoring the field (preferred)"),
    row: z.number().int().min(1).max(27).optional().describe("1-based field row (from the fields table)"),
    col: z.number().int().min(1).max(132).optional().describe("1-based field column (from the fields table)"),
    then_press: z.string().optional().describe('Optional AID key to press after typing, e.g. "ENTER", "F4"'),
    session_id: sid,
  },
  guard(async ({ value, label, row, col, then_press, session_id }) => {
    if (label === undefined && (row === undefined || col === undefined))
      throw new Error("Provide either label, or both row and col (see the fields table from screen_read).");
    const req = { cmd: "type", sessionId: session_id, value };
    if (label !== undefined) req.label = label;
    else Object.assign(req, { row, col });
    let resp = await rpc(req);
    let note = `Typed into ${label !== undefined ? `label ${JSON.stringify(label)}` : `row ${row} col ${col}`}.`;
    if (then_press) {
      const key = normalizeKey(then_press);
      resp = await rpc({ cmd: "key", sessionId: session_id, key });
      note += ` Pressed ${key}; new screen below.`;
    } else {
      note += " Not submitted yet — press an AID key when ready.";
    }
    return textResult(renderScreen(resp, note));
  })
);

server.tool(
  "screen_press",
  `Press one AID key and wait for the host's response screen (the daemon waits for keyboard-unlock + a stable buffer — never add your own sleeps). Valid keys: ${VALID_KEYS}. While recording, records a key step.`,
  {
    key: z.string().describe('AID key name, e.g. "ENTER", "F3", "PAGE_DOWN"'),
    session_id: sid,
  },
  guard(async ({ key, session_id }) => {
    const k = normalizeKey(key);
    const resp = await rpc({ cmd: "key", sessionId: session_id, key: k });
    return textResult(renderScreen(resp, `Pressed ${k}.`));
  })
);

server.tool(
  "screen_extract",
  'Read data off the CURRENT screen without changing it, using the exact target grammar of the CSV "extract" step (so a target you verify here can be pasted into a suite): "message" (row-24 line), "row:<n>" (one row), "rows:<start>-<end>[:<colStart>-<colEnd>]" (a range, e.g. a subfile/list), or "label:<text>" (the value right of a label — works on protected/display-only text too).',
  {
    target: z.string().describe('e.g. "label:CPU %", "row:3", "rows:9-19:2-40", "message"'),
    session_id: sid,
  },
  guard(async ({ target, session_id }) => {
    const snap = await rpc({ cmd: "screen", sessionId: session_id });
    const out = extractFromSnapshot(snap, target);
    if ("values" in out)
      return textResult(`extracted ${out.values.length} line(s) for ${JSON.stringify(target)}:\n` + out.values.join("\n"));
    return textResult(`extracted value for ${JSON.stringify(target)}: ${JSON.stringify(out.value)}`);
  })
);

// ---- recording tools

server.tool(
  "record_start",
  "Start recording live actions on a session into replayable CSV suite rows. After this, every screen_type / screen_press / session_connect / session_disconnect on the session is captured (with robust label-based targets inferred automatically). Use record_mark to add check/extract steps at the right screens, then record_stop to save. Starting again replaces an unsaved recording.",
  {
    case_name: z.string().default("recorded").describe("The 'case' id the recorded steps are grouped under"),
    session_id: sid,
  },
  guard(async ({ case_name, session_id }) => {
    await rpc({ cmd: "record-start", sessionId: session_id, case: case_name });
    return textResult(
      `Recording started on session "${session_id}" (case "${case_name}"). Perform the flow now; it is being captured. Tip: connect/signon inside the recording makes the suite self-contained — but type \${USER}/\${PASSWORD} style values via suite vars later, never a literal password.`
    );
  })
);

server.tool(
  "record_mark",
  'Insert an assertion or data-extraction step into the active recording at the current position. kind "check": verify screen content on replay (target + expected substring). kind "extract": pull a value into the run\'s structured output (target + name for the output column/file). Target grammar: "message", "row:<n>", "rows:<a>-<b>[:<c>-<d>]" (extract only), or "label:<text>". Verify the target first with screen_extract.',
  {
    kind: z.enum(["check", "extract"]),
    target: z.string().describe('e.g. "label:CPU %", "row:1", "message"'),
    expected: z.string().optional().describe("check only: substring the target must contain on replay"),
    name: z.string().optional().describe("extract only: output field name for the extracted value"),
    session_id: sid,
  },
  guard(async ({ kind, target, expected, name, session_id }) => {
    if (kind === "check") {
      const r = await rpc({ cmd: "record-mark-check", sessionId: session_id, target, expected: expected ?? "" });
      return textResult(`Recorded check step: ${JSON.stringify(r.recordedRow)}`);
    }
    if (!name) throw new Error('kind "extract" requires name (the output field name).');
    const r = await rpc({ cmd: "record-mark-extract", sessionId: session_id, target, name });
    return textResult(`Recorded extract step: ${JSON.stringify(r.recordedRow)}`);
  })
);

server.tool(
  "record_status",
  "Whether a recording is active on a session and how many steps it has captured so far.",
  { session_id: sid },
  guard(async ({ session_id }) => {
    const r = await rpc({ cmd: "record-status", sessionId: session_id });
    return textResult(r.active ? `Recording ACTIVE — ${r.count} step(s) captured.` : "Not recording.");
  })
);

server.tool(
  "record_stop",
  'Stop the active recording. With flow+file, saves the captured rows as a runnable suite (flow "custom-steps"; errors if the file exists unless overwrite=true). Without flow/file, returns the rows for inspection WITHOUT saving — they are then gone from the daemon, so save via suite_save if you want them. discard=true throws the recording away.',
  {
    flow: z.string().optional().describe('Flow folder to save into, normally "custom-steps"'),
    file: z.string().optional().describe("Suite file name (no .csv)"),
    overwrite: z.boolean().default(false).describe("Allow replacing an existing suite file"),
    discard: z.boolean().default(false).describe("Throw the recording away instead of saving/returning"),
    session_id: sid,
  },
  guard(async ({ flow, file, overwrite, discard, session_id }) => {
    if (discard) {
      await rpc({ cmd: "record-discard", sessionId: session_id });
      return textResult("Recording discarded.");
    }
    if ((flow && !file) || (!flow && file)) throw new Error("Provide both flow and file to save, or neither to just view rows.");
    if (flow && file && !overwrite && (await suiteExists(flow, file)))
      throw new Error(`Suite "${flow}/${file}" already exists. Use overwrite=true, or a different file name.`);
    const r = await rpc({ cmd: "record-stop", sessionId: session_id });
    const rows = r.rows || [];
    if (!rows.length) return textResult("Recording stopped: 0 steps captured (nothing to save).");
    if (flow && file) {
      const n = await saveSuite(flow, file, rows);
      const columns = await flowColumns(flow);
      return textResult(
        `Recording saved as suite "${flow}/${file}" (${n} rows):\n` +
          rowsToCsvText(columns, rows) +
          `\n\nNext: parameterize literals with \${NAME} via suite_save + vars, then prove it with suite_run.`
      );
    }
    return textResult(
      `Recording stopped (${rows.length} rows, NOT saved — daemon no longer holds them):\n` +
        rowsToCsvText(Object.keys(rows[0]), rows)
    );
  })
);

// ---- suite tools

server.tool(
  "suite_list",
  "List scenario flows and each flow's suite files (with row counts and whether a JS orchestrator script exists). Suites live under scenarios/<flow>/<file>.csv next to the daemon.",
  { flow: z.string().optional().describe("Limit to one flow (otherwise all flows)") },
  guard(async ({ flow }) => {
    const flowsResp = await httpJson("GET", "/api/flows");
    const flows = (flowsResp.flows || []).filter((f) => !flow || f.name === flow);
    if (!flows.length) throw new Error(`No such flow "${flow}".`);
    const lines = [];
    for (const f of flows) {
      lines.push(`flow "${f.name}" (columns: ${f.columns.join(", ")})`);
      const filesResp = await httpJson("GET", `/api/scenario-files?flow=${encodeURIComponent(f.name)}`);
      const files = filesResp.files || [];
      if (!files.length) lines.push("  (no suites)");
      for (const s of files) lines.push(`  ${s.name} — ${s.rows} rows${s.hasScript ? " [JS orchestrator]" : ""}`);
    }
    return textResult(lines.join("\n"));
  })
);

server.tool(
  "suite_get",
  "Read one suite: its step rows (as CSV text), its ${NAME} variables, and whether a JS orchestrator drives it. Reminder: an include'd suite does NOT inherit this suite's vars and vice versa — each file declares every var its own placeholders need.",
  {
    flow: z.string().describe('Flow folder, e.g. "custom-steps"'),
    file: z.string().describe("Suite file name (no .csv)"),
  },
  guard(async ({ flow, file }) => {
    if (!(await suiteExists(flow, file))) throw new Error(`No suite "${flow}/${file}". Use suite_list to see what exists.`);
    const rowsResp = await httpJson("GET", `/api/scenarios?flow=${encodeURIComponent(flow)}&file=${encodeURIComponent(file)}`);
    if (rowsResp.ok !== true) throw new Error(String(rowsResp.error));
    const varsResp = await httpJson("GET", `/api/scenario-vars?flow=${encodeURIComponent(flow)}&file=${encodeURIComponent(file)}`);
    const scriptResp = await httpJson(
      "GET",
      `/api/scenario-script?flow=${encodeURIComponent(flow)}&file=${encodeURIComponent(file)}`
    );
    const lines = [`suite "${flow}/${file}" — ${rowsResp.rows.length} rows`];
    lines.push(rowsToCsvText(rowsResp.columns, rowsResp.rows));
    const vars = varsResp.rows || [];
    lines.push(vars.length ? "\nvariables (.vars.csv):" : "\nvariables: none");
    for (const v of vars) lines.push(`  ${v.name} = ${v.value}`);
    if (scriptResp.exists) lines.push("\nA <file>.js orchestrator drives this suite (suite_run executes it).");
    return textResult(lines.join("\n"));
  })
);

server.tool(
  "suite_save",
  `Create or update a suite's steps and/or variables. rows: array of step objects using the flow's columns (custom-steps: case, step, action, target, value, expected; actions: type | key | check | extract | include | connect | wait | disconnect | if | else | endif | loop | endloop). Overwriting an existing file's rows requires overwrite=true (protects edits made concurrently in the GUI — suite_get first and merge). vars sets the \${NAME} substitution values (merged over existing ones). NEVER put a literal password in a row or var — use a \${PASSWORD} placeholder value like "your-pub400-password" and override at run time via suite_run vars.`,
  {
    flow: z.string().describe('Flow folder, normally "custom-steps"'),
    file: z.string().describe("Suite file name (no .csv)"),
    rows: z
      .array(z.record(z.string()))
      .optional()
      .describe("Complete replacement list of step rows (omit to leave steps untouched)"),
    vars: z.record(z.string()).optional().describe("Variable name → value map to merge into <file>.vars.csv"),
    overwrite: z.boolean().default(false).describe("Required to replace rows of an existing file"),
  },
  guard(async ({ flow, file, rows, vars, overwrite }) => {
    if (!rows && !vars) throw new Error("Nothing to save: provide rows and/or vars.");
    const exists = await suiteExists(flow, file);
    const out = [];
    if (rows) {
      if (exists && !overwrite)
        throw new Error(`Suite "${flow}/${file}" already exists. suite_get it, merge, then save with overwrite=true.`);
      const n = await saveSuite(flow, file, rows);
      out.push(`Saved ${n} step row(s) to "${flow}/${file}".`);
    }
    if (vars) {
      const cur = await httpJson("GET", `/api/scenario-vars?flow=${encodeURIComponent(flow)}&file=${encodeURIComponent(file)}`);
      const merged = {};
      for (const v of cur.rows || []) if (v.name) merged[v.name] = v.value ?? "";
      Object.assign(merged, vars);
      await saveVars(flow, file, merged);
      out.push(`Saved ${Object.keys(merged).length} variable(s): ${Object.keys(merged).join(", ")}.`);
    }
    return textResult(out.join("\n"));
  })
);

server.tool(
  "suite_run",
  "Run a suite end-to-end (exactly like the GUI's Run All / CLI run-suite) and wait for it to finish, returning per-scenario PASS/FAIL, failure messages, and all extracted data. vars override the saved .vars.csv for this run only — the right place for real credentials (e.g. from env). A suite with a 'connect' case runs from cold; otherwise the session must already be connected. If wait_seconds elapses first, returns the run_id — poll with suite_run_status. Step-by-step screen replays are persisted on disk, not returned.",
  {
    flow: z.string().describe('Flow folder, e.g. "custom-steps"'),
    file: z.string().describe("Suite file name (no .csv)"),
    vars: z.record(z.string()).optional().describe("Per-run ${NAME} overrides (not persisted)"),
    disconnect_on_finish: z.boolean().default(false).describe("Force-close the session after the run"),
    wait_seconds: z.number().int().min(1).max(600).default(120).describe("Max seconds to wait for completion"),
    session_id: sid,
  },
  guard(async ({ flow, file, vars, disconnect_on_finish, wait_seconds, session_id }) => {
    if (!(await suiteExists(flow, file))) throw new Error(`No suite "${flow}/${file}". Use suite_list to see what exists.`);
    // precheck: no connect case and no live session → clear error before the daemon's late one
    const rowsResp = await httpJson("GET", `/api/scenarios?flow=${encodeURIComponent(flow)}&file=${encodeURIComponent(file)}`);
    const hasConnect = (rowsResp.rows || []).some((r) => (r.action || "").trim().toLowerCase() === "connect");
    if (!hasConnect) {
      const sessions = await rpc({ cmd: "sessions" });
      if (sessions.sessions?.[session_id] !== "connected")
        throw new Error(
          `Suite "${flow}/${file}" has no 'connect' step and session "${session_id}" is not connected. ` +
            `session_connect first, or add a self-contained connect case to the suite.`
        );
    }
    const start = await httpJson("POST", "/api/scenarios/run", {
      flow,
      file,
      sessionId: session_id,
      vars: vars || {},
      disconnectOnFinish: disconnect_on_finish,
    });
    if (start.ok !== true) throw new Error(improveError(String(start.error)));
    const runId = start.runId;
    const deadline = Date.now() + wait_seconds * 1000;
    let state;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 500));
      const st = await httpJson("GET", `/api/scenarios/run-status?runId=${encodeURIComponent(runId)}`);
      if (st.ok !== true) throw new Error(String(st.error));
      state = st;
      if (st.status === "done" || st.status === "error") break;
    }
    if (state && (state.status === "done" || state.status === "error"))
      return textResult(`run ${runId} — ` + summarizeResults(state));
    return textResult(
      `run ${runId} still running after ${wait_seconds}s (currently: ${state?.current || "?"}, ` +
        `${state?.completed ?? 0}/${state?.total ?? start.total} done). Poll with suite_run_status.`
    );
  })
);

server.tool(
  "suite_run_status",
  "Status of a previously started run (from suite_run's run_id): running/done/error, the step currently executing, and — once finished — the same result summary suite_run returns.",
  { run_id: z.string().describe("The run id suite_run returned") },
  guard(async ({ run_id }) => {
    const st = await httpJson("GET", `/api/scenarios/run-status?runId=${encodeURIComponent(run_id)}`);
    if (st.ok !== true) throw new Error(improveError(String(st.error)));
    if (st.status === "done" || st.status === "error") return textResult(`run ${run_id} — ` + summarizeResults(st));
    return textResult(
      `run ${run_id}: ${st.status} — ${st.completed ?? 0}/${st.total ?? "?"} scenarios done` +
        (st.current ? `, executing: ${st.current}` : "")
    );
  })
);

// ---------------------------------------------------------------------- main

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`[five250-mcp] ready (daemon: ${BASE}, home: ${HOME})`);
