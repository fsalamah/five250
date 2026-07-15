# five250 — 5250 terminal automation rules

`five250` is a CLI over a local daemon that drives a live tn5250j session against
an IBM i host (PUB400) and returns the screen as **plain text data** — the 24x80
character buffer, cursor position, OIA (keyboard/inhibit state), and the input
field table. There are no pixels anywhere in this stack.

Binary: `five250/target/five250.jar` (build with `mvn package` inside `five250/`).
Run it as `java -jar target/five250.jar <command> ...`, or put `bin/` on PATH
and run `five250 <command> ...` directly. `five250 help [command]` gives full
usage/options/examples. The daemon auto-starts on first use and stays up
across commands (127.0.0.1:25250 TCP + 25251 HTTP/GUI); each CLI invocation
is a fast, separate JVM call that talks to the same long-lived daemon.

**Package is self-contained** (`Home.java`): `scenarios/` and `docs/` resolve
relative to the running jar's own directory, not the current working
directory (override with `FIVE250_HOME` if you want data elsewhere). Copy
`five250.jar` + `bin/` + `five250-completion.bash` + `CLAUDE.md` +
`scenarios/` anywhere and it works unchanged — verified live by running it
from a completely unrelated directory.

**CI / headless**: `five250 run-suite --flow F --file N [--var NAME=VALUE ...]`
drives a suite exactly like clicking Run All, prints each step + PASS/FAIL,
and exits 0/1/2/3 (pass/fail/run-error/timeout) — a real CI gate. `--var`
overrides that file's saved `.vars.csv` values for this run only.

## Connection

- Use **plain telnet, port 23** (`--host pub400.com --port 23`). SSL (`--ssl`,
  port 992) currently hangs during the TLS handshake in this tn5250j build —
  do not use it until that's debugged.
- Credentials come from environment variables you set yourself in your own shell
  (`PUB400_USER`, `PUB400_PASS`) — never write a literal password into a spec
  file, prompt, or commit.

## Core model

5250 is BLOCK MODE. One round-trip = fill fields → press an AID key
(ENTER / F1–F24 / PA1-PA3 / PAGEUP / PAGEDOWN / ...) → a new screen arrives.
There are no events, no async DOM. Never poll for a DOM-style element.

## NEVER

- Never use a raw `sleep()` to wait for a screen. The daemon already does the
  correct sync internally (wait for keyboard-unlocked, then wait for the
  buffer hash to stabilize across 3 polls) before returning from `key`/`type`/
  `signon`/`connect`. If a screen still looks stale, that's a bug to fix in
  `Terminal.waitReady`, not something to paper over with a sleep in a test.
- Never hardcode row/col for a field you haven't verified. Run `five250 fields`
  or `five250 screen` first and read the actual output.
- Never guess a screen's contents or field labels. Run `five250 screen` before
  writing any assertion or `type --label`.

## ALWAYS

- Run `five250 screen` before writing any assertion or locator.
- Prefer `type --label "<exact label text>"` over `type --row --col` — labels
  survive DDS layout changes, coordinates don't. Use `five250 fields` to see
  the row/col/length/protected/numeric flags for every field on the current
  screen if label anchoring fails.
- Identify each screen by a signature: distinctive text at a known row
  (e.g. row 1 title). Document it in `docs/screens.md`.
- Assert on the row-24 message line for errors, not on color (this build
  doesn't expose per-character attribute bytes yet — see Known limitations).

## Authoring loop (live-first)

1. `five250 connect --host pub400.com --port 23`
2. `five250 signon --user "$PUB400_USER" --pass "$PUB400_PASS"`
3. Drive the live session by hand via the CLI, running `five250 screen` after
   every `type`/`key`, and record what you see in `docs/screens.md`.
4. Only write test/ScreenObject code after you've observed the real screens.
5. `five250 disconnect` when done with a session.

## Screen inventory

Document every screen discovered in `docs/screens.md` as you go: signature
text + row, input fields (label, row, col, length, numeric/protected), valid
F-keys, and how paging/subfile behavior works.

## GUI + scenario engine

The daemon also serves a local web GUI at **http://127.0.0.1:25251** (starts
automatically alongside the TCP protocol on 25250 — same `java -jar
target/five250.jar connect ...` call brings both up). Two tabs:

- **Terminal** — connect/signon, live screen view, type-into-field (with CL
  command autocomplete + an IDE-style docs panel, sourced from
  `web/cl-commands.json`), and an AID-key keypad. Human-usable version of the
  CLI, for manual exploration when mapping a new screen.
- **Scenarios** — a project explorer. Pick a `Flow`, then CRUD its CSV files
  (create/rename/delete, each a real file under `scenarios/<flow>/`), CRUD
  rows within a file, edit its **Variables** panel, and **Run All** — runs
  asynchronously with a live progress panel: scenario count, the exact step
  currently executing, and (unless Headless is checked) the live 5250 screen
  updating in near-real-time via polling. Before running, checks the target
  session is actually connected and refuses with a clear message instead of
  a raw "no session" error if not.

### Two ways to add automation

1. **Pure CSV, no code (`custom-steps` flow)** — this is the one to reach for
   first. Each scenario is a group of rows sharing a `case` id, executed in
   `step` order:
   `case, step, action(type|key|check|extract|include|connect|wait), target, value, expected`.
   `type` target is `label:<text>` (preferred) or `<row>,<col>`; `check`/
   `extract` target is `message`, `row:<n>`, or `label:<text>`; `include`
   target is another CSV file name (no `.csv`) in the same flow folder — its
   steps are spliced in at that point, so one suite can reuse another
   (cycle-checked; see `scenarios/custom-steps/signon-common.csv` +
   `full-signon-v2.csv` for a worked example). Straight-line navigation with
   reuse; no conditional branching/looping.

   `extract` (value = output field name) pulls a value off the screen into
   the result's structured output — a CSV/JSON row, not a pass/fail check.
   Its `label:` addressing reads straight off the character buffer
   (`Terminal.readAfterLabel`, stop at 2+ spaces or end of row), so it works
   for **protected/display-only** text (a balance, a job count) — unlike
   `type`'s label targeting, which only touches editable `ScreenField`s.
   Landed data shows up as its own column in `.results.csv`, in the JSON
   `results[].extracted` map, and in the GUI's Results table. See
   `scenarios/custom-steps/extract-demo.csv` — pulls active job count, CPU%,
   and elapsed time off a protected line of WRKACTJOB. Table/subfile
   scraping (multi-row, multi-page extraction) is a bigger follow-up, not
   built yet — this covers single-value extraction only.

   `connect` makes a suite fully self-contained — no prior manual Connect
   click needed. Put it alone in its own case (target=host, value=port,
   expected="true" for SSL); `HttpApi.autoConnectIfNeeded()` intercepts it
   before the run starts, connects only if that session doesn't already
   exist, then strips the whole pseudo-case either way. See
   `scenarios/custom-steps/self-contained-signon.csv` — runs correctly from
   a completely cold, zero-sessions daemon state, verified live. Careful:
   re-running an unconditional-signon suite on an *already* signed-on
   session will fail (the sign-on fields won't exist wherever it lands) —
   that's correct behavior, not a bug, given there's no branching yet.

   `wait` (value = seconds, 0-120 capped) is a deliberate, opt-in exception
   to "never sleep" — use it only for delays outside the 5250 buffer
   (a batch job finishing) that `waitReady()`'s keyboard/buffer-stability
   polling can't detect. See `scenarios/custom-steps/wait-test.csv`.

2. **A new `Flow` class, for anything conditional** — write a `Flow`
   implementation (see `RunCommandFlow.java` for the per-row pattern, or
   `GenericStepFlow.java` for the grouped/multi-step + include pattern),
   register it in `FlowRegistry`, rebuild. The CSV columns come from
   `Flow.csvColumns()` and are entirely data from then on.

**Variables**: any cell in any flow's CSV may contain `${NAME}` — resolved
from `scenarios/<flow>/<file>.vars.csv` (name/value pairs, editable in the
GUI's Variables panel) before the row/case runs. Substitution happens after
`include` expansion, so a suite that includes another must also define any
variables the included suite's placeholders need — variables are **not**
inherited automatically from included files (see `full-signon-v2.vars.csv`,
which redeclares `USER`/`PASSWORD` on top of its own `COMMAND`/
`EXPECTED_TITLE`). If this bites people, the fix is to merge included files'
`.vars.csv` too — not done yet, deliberately kept simple for v1.

Scenario files live in `scenarios/<flow-name>/<file-name>.csv` (folder per
flow, multiple named files each — a real project explorer, not one fixed
file). Results are written to `scenarios/<flow-name>/<file-name>.results.csv`
after each run; failing scenarios also get a full screen-buffer dump at
`docs/samples/failures/<flow-name>/<file-name>/row-N.json` (cheap — ~2KB
each, capture liberally).

**Replay**: every scenario captures a full screen snapshot at every step
(pass or fail, not just failures) into `ScenarioResult.steps` — see
`GenericStepFlow.runGroup()` and `RunCommandFlow.run()`, both call
`result.step(label, t.snapshot())` after each meaningful action. Persisted
to `docs/samples/replays/<flow-name>/<file-name>/row-N.json` after every run
(`ScenarioRunner.writeReplays`), fetchable later via `GET
/api/scenarios/replay?flow=&file=&index=`. The GUI's Results table shows a
"▶ Replay (N)" button per scenario when steps exist — opens a modal with
Prev/Next through the actual captured screens, for stepping through exactly
what happened after the fact.

Runs are asynchronous: `POST /api/scenarios/run` returns `{runId, total}`
immediately: a background thread executes and updates a `RunTracker.RunState`
(`status`, `current`, `completed`, `results`) that `GET
/api/scenarios/run-status?runId=` polls. `Progress.report(...)` is a
ThreadLocal reporter — call it from inside a `Flow` implementation to surface
"what's executing right now" without threading a callback through every
method signature; whoever starts the run thread installs the listener via
`Progress.set(...)`.

The HTTP API (`HttpApi.java`) is a thin JSON wrapper: `/api/rpc` mirrors the
TCP protocol 1:1 (`SessionService.handle`), so the GUI and the CLI drive the
exact same session logic — no duplicated navigation code anywhere.

## CLI help and shell completion

- `five250 help` lists every command; `five250 help <command>` shows full
  usage, options, and examples (`HelpText.java` — keep it in sync when adding
  a CLI command; it's also the source `printUsage()` falls back to).
- `bin/five250` is a thin bash wrapper (`exec java -jar .../five250.jar "$@"`)
  so the tool is a real command on PATH — needed for completion to register
  against something other than `java -jar ...`.
- `five250-completion.bash` — tab-completion for subcommands, per-command
  flags, and AID key names. `source` it (see the file's header comment).

## CL command autocomplete data

`web/cl-commands.json` — curated `{name, syntax, description, params[]}`
entries powering the GUI's autocomplete/docs panel. Currently ~105 commands;
IBM i has roughly 1,500–2,000 total, so this is a useful working set, **not**
exhaustive — don't claim full coverage. To regenerate authoritatively from a
real system: `SELECT CMD_NAME, CMD_LIBRARY, CMD_TEXT FROM QSYS2.SYSCMDS
ORDER BY CMD_NAME` via ACS Run SQL Scripts or STRSQL, export to CSV, convert.

## Known limitations (v1)

- No per-character attribute bytes (color / reverse-video / underline) yet —
  `screen` returns text + field metadata (numeric/protected) but not raw 5250
  attribute planes. Good enough for navigation and text assertions; revisit
  `Terminal.snapshot()` / `Screen5250` (`ScreenPlanes`) if you need
  reverse-video detection for error highlighting.
- SSL (port 992) hangs — see Connection above. Use plain port 23.
- One JVM process per CLI invocation (~150ms startup) talking to a persistent
  daemon process that holds the actual session — this is intentional (Phase 3
  "sidecar" from the design doc), not a bug.
