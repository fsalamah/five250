# five250 MCP server — design record

Goal: expose five250 (5250 terminal automation daemon) to an AI model over MCP so the
model can work exactly like a professional automation engineer does: **navigate →
observe → record → parameterize → test → harden**, never acting blind — every action
returns the resulting screen plus detected input fields.

## Architecture decision

**Thin Node.js stdio MCP server wrapping the daemon's existing HTTP API
(http://127.0.0.1:25251)** — official `@modelcontextprotocol/sdk`.

Why (confidence 98%):
- The daemon already owns everything hard: the live tn5250j session, correct
  synchronization (`Terminal.waitReady` — keyboard-unlock + buffer-hash stability, so
  no sleeps anywhere), label→field resolution, the recording engine, the CSV/vars/JS
  scenario engine, results/extract/replay persistence. Reimplementing any of that in
  another runtime would duplicate session state and immediately drift.
- `/api/rpc` mirrors the TCP protocol 1:1 (`SessionService.handle`), and the scenario
  REST endpoints (`/api/scenario-files`, `/api/scenarios`, `/api/scenario-vars`,
  `/api/scenarios/run`, `/api/scenarios/run-status`) cover the whole suite lifecycle.
  The CLI and GUI already share this exact surface; the MCP server becomes a third
  equal client, so GUI, CLI and model always see the same sessions and files.
- stdio transport: standard for local single-user servers; the daemon stays the single
  stateful process, the MCP server is stateless glue and can restart freely.
- Node 23 + official TypeScript SDK: protocol compliance for free; all logging to
  stderr (stdout is the JSON-RPC channel).

The server auto-starts the daemon if 25251 is unreachable (spawns
`java -jar <FIVE250_JAR> serve` with `FIVE250_HOME` set — honoring the dev-tree
gotcha in CLAUDE.md: without `FIVE250_HOME` a jar run from `target/` silently uses
`target/scenarios/`).

## Review → critique → refine iterations

### Iteration 1 — v0: mirror the RPC surface 1:1

Design: one MCP tool per RPC/REST verb (~20 tools: connect, signon, screen, fields,
type, key, sendtext, setcursor, infer, record-*, sessions, disconnect, shutdown,
scenario-files CRUD, scenarios CRUD, vars, data, script, run, run-status, replay),
raw JSON responses passed through.

Critique:
- `shutdown` exposed to a model is a foot-gun (kills the daemon the GUI user is
  using). Confidence it should exist: 5% → **removed**.
- `sendtext`/`setcursor`/`infer` are GUI-fidelity plumbing; a model plans in terms of
  fields, not cursor choreography. Keeping them adds choice-paralysis. → **removed**
  (typeAt/typeLabel cover every real need; `infer` runs implicitly server-side in the
  daemon when recording).
- Raw snapshot JSON (`text` as one 1920-char string, 0 newlines) is hostile to an LLM:
  it cannot count columns in an unbroken string. Confidence raw JSON is right: 10% →
  **replaced** with a rendered screen: numbered rows, a SEU-style column ruler, cursor,
  OIA state, message line, and a compact field table.
- File rename/delete tools: Anthropic guidance says avoid destructive endpoints unless
  necessary; a model authoring suites needs create/read/update, not delete. → **cut**.

### Iteration 2 — v1: consolidated workflow tools

Design: `session_*` (connect/signon/disconnect/list), `screen_read`, `screen_type`,
`screen_press`, `record_*`, `suite_list/get/save/run/run_status`.

Critique:
- **Ad-hoc extraction gap**: the CSV `extract` action reads protected text
  (`label:`/`row:`/`rows:`/`message`), but there is no RPC for it — a model exploring
  live data would have to eyeball the rendered screen and re-type values (error-prone
  for the "data extraction" mission). Confidence gap is real: 97% → **added
  `screen_extract`**, implemented in the MCP layer over the snapshot text using the
  exact same target grammar as the CSV `extract` action (so what the model tests ad
  hoc is copy-pasteable into a suite step).
- `suite_run` returning only a runId forces a poll loop through the model = wasted
  turns. → **made synchronous up to `wait_seconds` (default 120)**, polling
  run-status internally at 500ms; returns `run_id` + partial status only on timeout,
  with `suite_run_status` for follow-up.
- Fill-3-fields-then-ENTER is one 5250 round trip but three tool calls. Considered a
  batched `screen_submit(fields[], key)`. Rejected full batching (recording fidelity +
  per-field error attribution suffer; a failed label mid-batch leaves ambiguity), but
  **added optional `then_press` to `screen_type`** — the dominant 2-step pattern
  (type value, press ENTER) becomes one call, still recorded as two CSV rows by the
  daemon. Confidence: 95%.

### Iteration 3 — recording/authoring lifecycle holes

Critique:
- `record_stop` returned rows but offered no path to a saved suite — the model would
  have to re-enter them via `suite_save`, risking transcription drift. → **`record_stop`
  can save directly** (`flow`+`file`), and prepends a self-contained
  `connect` setup case if the recording captured one (`recordedRow` connect support
  exists in the daemon).
- `suite_save` blindly overwriting a file the human just edited in the GUI is a real
  race (GUI and MCP share the daemon). → **`overwrite: true` required** to replace an
  existing file's rows; default errors with a message telling the model to
  `suite_get` first. Confidence: 96%.
- Variables gotcha from CLAUDE.md: included files do NOT inherit the includer's
  `.vars.csv`. A model composing `include signon-common` + `${USER}` would hit this
  blind. → **documented in `suite_save`/`suite_get` tool descriptions** ("a suite that
  `include`s another must redeclare the included suite's `${NAME}` variables in its
  own vars").
- Two `record_mark_*` RPCs → **one `record_mark` tool** with `kind: "check"|"extract"`
  (same shape, less surface).

### Iteration 4 — failure modes, environment, protocol hygiene

Critique:
- Daemon down → every tool failing with ECONNREFUSED is opaque. → **auto-start**: on
  connection failure, spawn the jar (env `FIVE250_JAR`, default
  `<repo>/target/five250.jar`; `FIVE250_HOME` default `<repo>`), wait for `/api/flows`
  to answer (10s), retry once; otherwise return an actionable error naming the exact
  command to run. Confidence auto-start is safe: 95% (idempotent — if another daemon
  wins the port race, the health check still passes).
- Dev-tree gotcha (memory + CLAUDE.md): auto-start MUST set `FIVE250_HOME` or
  recordings land in `target/scenarios/`. → baked in.
- stdio protocol corruption: any stray `console.log` breaks the client. → **all
  diagnostics via `console.error`**; SDK owns stdout.
- Error surfacing: daemon errors come back as `{ok:false,error}`; raw pass-through
  loses actionability. → **error mapper** appends the fix: "No session" → "call
  session_connect first"; "Unknown key" → list of valid AID keys; "No input field at
  row/col" → "run screen_read and use the fields table"; ECONNREFUSED → daemon start
  instructions. Tool failures are `isError: true` results (per MCP spec), never
  protocol errors, so the model can read and recover.
- Key names: validated client-side against the daemon's `KeyMap` grammar
  (ENTER, F1–F24/PF1–PF24, PA1–PA3, PAGE_UP/DOWN, TAB, BACK_TAB, HOME, CLEAR, HELP,
  SYSREQ, RESET, ATTN, ERASE_EOF...) with normalization, so typos fail fast with the
  full list instead of a daemon round trip.

### Iteration 5 — security, size limits, model ergonomics

Critique:
- **Credential leakage** is the top risk: if the model passes `pass` as a tool
  argument it lands in the conversation transcript and possibly a recorded CSV. →
  `session_signon` accepts `user_env`/`pass_env` (names of environment variables,
  default `PUB400_USER`/`PUB400_PASS`) and resolves them **inside the MCP server
  process**; literal `user`/`pass` params exist for non-pub400 hosts but the
  description explicitly steers to env mode. Passwords are never echoed in any
  response. Recording note in the description: type `${PASSWORD}` style placeholders
  into suites, never literals.
- Response size: a screen render is ~2.5 KB — fine. But `screen_extract rows:1-24`
  and `suite_run` results with replays could balloon. → extract capped at rows
  requested (max 24 anyway, geometry-validated); `suite_run` returns per-scenario
  status/message/extracted only — **never** the step-by-step replay screens (those
  stay on disk; the description says where). `suite_get` returns rows as compact
  aligned CSV text, not verbose JSON.
- OIA in every render: if `inputInhibited`/`keyboardLocked` is true the model must
  not type — surfaced as an explicit `keyboard LOCKED`/`input inhibited` banner, and
  `messageWait` shown, because that is how a professional reads the OIA line.
- Server `instructions` (sent at initialize) teach the 5250 mental model once instead
  of repeating it in every description: block mode (fill fields → AID key → new
  screen), never guess labels (read the screen first), prefer `label:` over
  coordinates, row-24 message line for errors, the recording workflow, and the
  self-contained-suite pattern (connect case + include + vars).
- Naming: `session_` / `screen_` / `record_` / `suite_` prefixes group the 16 tools
  into the four workflow phases (prefix namespacing per Anthropic guidance).

### Iteration 6 — final pass (all points ≥95%)

Re-attack of the refined design found two residual issues, both fixed:
- `screen_type` with `then_press` on a failing type would leave "did the key fire?"
  ambiguous → type errors abort before the key is sent, and the error says so.
- `suite_run` on a suite with no `connect` case and no live session: the daemon
  errors late. → precheck via `sessions` RPC + suite content; error tells the model
  to either add a connect case or `session_connect` first.
- Confidence review (all ≥95%): thin-wrapper architecture 98; stdio 99; tool set
  completeness for navigate/observe/record/parameterize/run/extract 96; rendered
  screen format 97; env-based credentials 97; synchronous run with timeout 96;
  overwrite guard 96; auto-start 95; error mapper 95.

## Final tool list (16)

| tool | purpose |
|---|---|
| `session_connect` | open a 5250 session (host/port/ssl/session_id) → rendered screen |
| `session_signon` | sign on (env-var credentials by default) → rendered screen |
| `session_disconnect` | close + deregister a session |
| `session_list` | list sessions and connected state |
| `screen_read` | current screen: numbered rows + ruler, cursor, OIA, message line, field table |
| `screen_type` | put a value in a field by `label` or `row`+`col`; optional `then_press` AID key |
| `screen_press` | press an AID key, wait for the next screen |
| `screen_extract` | read data off the live screen with the CSV `extract` target grammar |
| `record_start` | start recording actions on a session into CSV rows |
| `record_mark` | insert a `check` or `extract` step at the current point |
| `record_status` | is recording active + row count |
| `record_stop` | stop; return rows and/or save them as a suite (or discard) |
| `suite_list` | flows and their suite files |
| `suite_get` | one suite's steps + variables + script flag |
| `suite_save` | create/update a suite's steps and variables (overwrite-guarded) |
| `suite_run` | run a suite (vars overrides), wait for completion, return results + extracted data |
| `suite_run_status` | poll a still-running run |

(17 rows listed; `record_status` was folded in during implementation as a cheap
read-only tool — kept because "am I still recording?" is a real recovery question
after an error mid-recording.)

## Known limitations

- No attribute planes (color/reverse-video) — upstream five250 v1 limitation.
- JS-orchestrator suites (`<file>.js`) run fine via `suite_run`, but the MCP server
  does not author `.js` files (use `suite_save` for CSV + the GUI/editor for JS).
- One flow (`custom-steps`) is where models should author; `run-command` flow is
  listed but its columns differ (`suite_save` validates against the flow's columns).
- SSL to pub400 hangs in this tn5250j build (documented) — default is plain 23.
