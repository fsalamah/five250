# five250 MCP server

An MCP (Model Context Protocol) server that lets an AI model drive five250's live
5250 terminal sessions and its scenario/suite engine — explore screens, record
automation, parameterize it, run it, and extract data — through 17 tools that
mirror how a professional automation engineer works. It is a thin stdio wrapper
over the five250 daemon's HTTP API (`http://127.0.0.1:25251`); all session and
suite state lives in the daemon, which the browser GUI and CLI share.

See `DESIGN.md` for the architecture rationale and the review/critique iterations,
and `TEST-REPORT.md` for the live pub400.com test evidence.

## Install

```bash
cd mcp
npm install          # @modelcontextprotocol/sdk + zod
```

Requirements: Node 18+ (uses global fetch), Java on PATH (only for daemon
auto-start), and a built `five250.jar`.

## Register with Claude Code

```bash
claude mcp add five250 \
  --env PUB400_USER=your-user --env PUB400_PASS=your-password \
  -- node C:/acabes/as400/five250/mcp/server.mjs
```

(Or add the equivalent `mcpServers` block to any MCP client config:
command `node`, args `[".../mcp/server.mjs"]`.)

Environment variables:

| var | default | purpose |
|---|---|---|
| `PUB400_USER` / `PUB400_PASS` | — | credentials `session_signon` uses when no literal user/pass argument is given (preferred: the password never enters the conversation) |
| `FIVE250_URL` | `http://127.0.0.1:25251` | daemon HTTP API base |
| `FIVE250_JAR` | `<repo>/target/five250.jar` | jar used for daemon auto-start |
| `FIVE250_HOME` | `<repo>` | passed to the auto-started daemon so `scenarios/` resolves to the real project tree (not `target/scenarios/`) |

If the daemon is not running, the server auto-starts it (and says so on stderr).

## Tools

Session:
- `session_connect(host, port=23, ssl=false, session_id)` — open a session, returns the first screen. Idempotent if already connected.
- `session_signon(user?, pass?, user_env=PUB400_USER, pass_env=PUB400_PASS, session_id)` — sign on; env-var credentials by default, password never echoed.
- `session_disconnect(session_id)` — close + deregister (recorded as a `disconnect` step while recording).
- `session_list()` — sessions and connected state.

Screen (observe + act — every action returns the resulting screen render:
numbered rows, column ruler, cursor, keyboard/OIA state, row-24 message line,
editable-field table with inferred labels):
- `screen_read(session_id)` — look, no action.
- `screen_type(value, label?|row?+col?, then_press?, session_id)` — fill one field; optionally press an AID key in the same call.
- `screen_press(key, session_id)` — ENTER, F1–F24, PA1–PA3, PAGE_UP/DOWN, ... waits for the next screen properly (no sleeps).
- `screen_extract(target, session_id)` — read data off the live screen using the CSV `extract` grammar: `message`, `row:<n>`, `rows:<a>-<b>[:<c>-<d>]`, `label:<text>` (works on protected text).

Recording:
- `record_start(case_name, session_id)` — start capturing type/key/connect/disconnect actions as CSV suite rows.
- `record_mark(kind: check|extract, target, expected?/name?, session_id)` — insert assertions / data pulls at the current screen.
- `record_status(session_id)` — active? how many steps?
- `record_stop(flow?, file?, overwrite?, discard?, session_id)` — save as a suite, return rows unsaved, or discard.

Suites:
- `suite_list(flow?)` — flows and suite files.
- `suite_get(flow, file)` — steps (CSV text), `${NAME}` variables, JS-orchestrator flag.
- `suite_save(flow, file, rows?, vars?, overwrite?)` — create/update steps and variables; overwriting existing steps requires `overwrite=true`.
- `suite_run(flow, file, vars?, disconnect_on_finish?, wait_seconds=120, session_id)` — run and wait; returns per-scenario PASS/FAIL and extracted data. `vars` override `.vars.csv` for that run only (the right place for real credentials).
- `suite_run_status(run_id)` — poll a run that outlived `wait_seconds`.

## Testing

- `node test/smoke.mjs` — offline protocol smoke test (initialize, tools/list, an error path).
- `PUB400_USER=... PUB400_PASS=... node test/live-test.mjs` — full live test against
  pub400.com through a real stdio MCP client: 68 positive + negative checks
  covering every tool. Exit 0 = all pass. Passwords are scrubbed from output.

## Security notes

- Prefer env-var sign-on; never pass a literal password if env vars are set.
- Never save a literal password into a suite: record with placeholders or
  parameterize to `${PASSWORD}` and keep a placeholder in `.vars.csv`, supplying
  the real value per run via `suite_run` `vars`.
- The server only talks to `127.0.0.1` and never exposes a network port itself.
