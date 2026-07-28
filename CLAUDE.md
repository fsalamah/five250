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
Running the jar with **no command at all** (or `five250 serve`) just starts
the daemon+GUI and stops there — for driving everything, Connect included,
from the browser instead of the CLI.

**Package is self-contained** (`Home.java`): `scenarios/` and `docs/` resolve
relative to the running jar's own directory, not the current working
directory (override with `FIVE250_HOME` if you want data elsewhere). Copy
`five250.jar` + `bin/` + `five250-completion.bash` + `CLAUDE.md` +
`scenarios/` anywhere and it works unchanged — verified live by running it
from a completely unrelated directory.

**Dev-tree gotcha, learned the hard way**: running `java -jar target/five250.jar
...` (the natural thing to do after `mvn package`, straight from the source
tree) means `Home.DIR` resolves to `target/`, NOT the project root — so the
daemon silently reads and writes `target/scenarios/`, a build artifact
directory `mvn clean` deletes, completely separate from the git-tracked
`scenarios/` next to `pom.xml`. `Csv.read()` returns an empty list for a
missing file with no error, so a suite silently runs "0 scenarios" instead of
failing loudly — and worse, anything recorded live (via the GUI's Record
feature) lands in `target/scenarios/`, invisible to git, at risk of being
deleted by the next clean build. When iterating in the dev tree, always
launch with `FIVE250_HOME=<project root> java -jar target/five250.jar ...`,
or copy the built jar up to the project root first (matching how the actual
packaged distribution is laid out — jar and `scenarios/` as siblings).

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

### Three ways to add automation

1. **Pure CSV, no code (`custom-steps` flow)** — this is the one to reach for
   first. Each scenario is a group of rows sharing a `case` id, executed in
   `step` order:
   `case, step, action(type|key|check|extract|include|connect|wait|disconnect), target, value, expected`.
   `disconnect` closes the session outright - recorded automatically when the
   live session actually disconnects, so replay reaches the same end state
   the recording did (no lingering connection the live run never had).
   `type` target is `label:<text>` (preferred) or `<row>,<col>`; `check`/
   `extract` target is `message`, `row:<n>`, or `label:<text>`; `include`
   target is another CSV file name (no `.csv`) in the same flow folder — its
   steps are spliced in at that point, so one suite can reuse another
   (cycle-checked; see `scenarios/custom-steps/signon-common.csv` +
   `full-signon-v2.csv` for a worked example). Straight-line navigation with
   reuse; no conditional branching/looping.

   `extract` (value = output field name) pulls a value off the screen into
   the result's structured output — a CSV/JSON row, not a pass/fail check.
   Its `label:`/`row:`/`message` addressing reads straight off the character
   buffer (`Terminal.readAfterLabel`/`rowText`), so it works for
   **protected/display-only** text (a balance, a job count) — unlike `type`'s
   label targeting, which only touches editable `ScreenField`s. `target` also
   accepts `rows:<start>-<end>[:<colStart>-<colEnd>]` for multi-row/subfile
   scraping (a WRKACTJOB job list, a WRKSPLF spool list) — the output is a
   list of lines, not a single string; see `GenericStepFlow.doExtractRows`.

   Landed data shows up three places: its own column in `results/<flow>/
   <file>.results.csv` (a `rows:` list joined with `" | "`), the JSON
   `results[].extracted` map (a real array there), and the GUI's Results
   table — AND, separately, as its own plain-text file:
   `extracted/<flow>/<file>.<name>.data.txt` (`<name>` is whatever the
   engineer put in that extract step's `value` cell), one line per extracted
   value/row, written by `ScenarioRunner.writeExtractedDumps` right alongside
   a timestamped `.<ts>.data.txt` copy (so a later run never clobbers an
   earlier dump) — for piping straight into another tool without touching
   the results CSV at all. See `scenarios/custom-steps/extract-demo.csv` —
   pulls active job count, CPU%, and elapsed time off a protected line of
   WRKACTJOB.

   `connect` makes a suite fully self-contained — no prior manual Connect
   click needed. Put it alone in its own case (target=host, value=port,
   expected="true" for SSL); `HttpApi.autoConnectIfNeeded()` intercepts it
   before the run starts, connects only if that session doesn't already
   exist, then strips the whole pseudo-case either way. See
   `scenarios/custom-steps/self-contained-signon.csv` — runs correctly from
   a completely cold, zero-sessions daemon state, verified live. Careful:
   re-running an unconditional-signon suite on an *already* signed-on
   session will fail (the sign-on fields won't exist wherever it lands) —
   that's correct behavior, not a bug: `connect` only ever runs once, before
   the suite starts, regardless of `if`/`loop` branching inside the suite
   itself.

   `wait` (value = seconds, 0-120 capped) is a deliberate, opt-in exception
   to "never sleep" — use it only for delays outside the 5250 buffer
   (a batch job finishing) that `waitReady()`'s keyboard/buffer-stability
   polling can't detect. See `scenarios/custom-steps/wait-test.csv`.

   **`if`/`else`/`endif` and `loop`/`endloop`** give a case real control
   flow. `if`'s `target` is a JS boolean expression, e.g.
   `extracted.balance > 100 && extracted.status == "ACTIVE"`. `${NAME}` vars
   are already resolved to literal text before a step reaches the
   interpreter, so a condition referencing a saved variable is just plain JS
   by the time it's evaluated (`${BALANCE} > 100` becomes, after
   substitution, `350 > 100`) — no extra binding needed. Only
   `extracted.<name>` needs one, since a value an earlier `extract` step in
   *this same case* pulled off the screen doesn't exist yet at substitution
   time; numeric-looking extracted strings are auto-coerced to real JS
   numbers first (`"9" > "10"` is `true` as a string compare, `false` as a
   number — 5250 screen data is always text, so this matters for every
   numeric condition). `loop`'s `target` picks the mode: `while` (its
   `value` is a JS condition, re-evaluated every time control returns via
   `endloop` — e.g. page a subfile with PAGE_DOWN, extract a "more"
   indicator, `loop while extracted.more == true`) or `count` (its `value`
   is an exact iteration count, capped at 500). Blocks may nest; an
   unmatched or mismatched marker (`endif` with no `if`, etc.) is caught
   before the case runs at all, not as a mid-run jump bug. Every step
   actually executed — including repeated loop-body visits — counts against
   a 2000-visit-per-case safety cap, so a condition that's always true fails
   the scenario with a clear error instead of hanging the run.

   The JS itself runs in `JsCondition` — a sandboxed, standalone-Nashorn
   engine (no GraalJS: a lighter dependency fits a self-contained single-jar
   tool better). Sandboxed because suite CSVs are exactly the kind of file
   people share/paste from elsewhere: a `ClassFilter` denies every Java
   class (no `Java.type(...)`, filesystem, sockets — pure ECMAScript only),
   and every evaluation runs under a 1-second wall-clock timeout on a pooled
   worker thread, so a pathological expression fails fast instead of
   hanging. See `scenarios/custom-steps/if-loop-demo.csv` for a worked
   example — self-contained (its own `connect` + `include signon-common`, so
   it opens and runs cleanly in the GUI from a cold session), demonstrating
   `if`/`else` on an extracted title, `loop while` (a real screen value
   naturally converging), and `loop count`.

2. **`<file>.js` driving `<file>.csv`, for real JS control flow instead of the
   `if`/`loop` mini-language** — a suite with a sibling `.js` file is driven
   entirely by that script (`JsSuiteRunner`) instead of `GenericStepFlow`
   walking every case automatically. The script gets a global object named
   after the suite's base filename (non-identifier characters like `-`
   sanitized to `_`) exposing:
   - `.vars` — a live, two-way bound object over the SAME map `${NAME}`
     substitution reads from. `suiteX.vars.username = 'abc'` before an
     `execute()` call feeds that value into substitution for that call; an
     `extract` step inside an executed range writes its result back into
     this same map (`StepActions.executeAction`), so
     `console.log(suiteX.vars.transactionAmount)` right after reads it back.
     Unlike plain CSV, substitution is NOT one-shot up front for these suites
     — it happens fresh on every `execute()` call, against whatever's
     currently in `.vars`.
   - `.steps(a, b)` — a range over the suite's rows, addressed by absolute
     1-based row position in the file (matching what you see in the CSV/GUI
     table directly), not by `case`/`step` column values.
   - a global `execute(range)` function that runs that range against the
     live terminal right now, in order.

   This means real `for`/`while`/functions/`try`/`throw` sequencing recorded
   step-ranges, instead of learning `if`/`else`/`endif`/`loop`/`endloop`.
   Sandboxed with `HostAccess.EXPLICIT` (only the bound suite object/
   `execute`/`console` are reachable, no arbitrary Java classes) rather than
   `JsCondition`'s full no-host-access sandbox, since the script has to call
   back into Java to drive the terminal at all — a deliberately bigger bridge
   for a deliberately more powerful authoring mode. A whole-script wall-clock
   timeout (5 minutes) is enforced by force-cancelling the GraalJS `Context`
   from the calling thread (`context.close(true)`) if exceeded — verified to
   actually stop a running script, not just abandon it.

   Limitation: `include`/`connect` rows aren't executable via `execute()`
   (they're resolved by the surrounding pipeline before a JS-orchestrated run
   ever starts, same as for plain CSV) — a `connect` case still runs
   automatically for the same cold-start convenience, just don't reference
   its row from the script. See `scenarios/custom-steps/js_orchestrator_demo.js`
   for a worked example: self-contained, sets a var before `execute()`, reads
   one back after an `extract`, and uses a real JS `for` loop calling
   `execute()` repeatedly — the JS-native analog of `loop count`.

   **VSCode autocomplete for `.js` orchestrators**: every steps-table save
   (`PUT /api/scenarios`) and every Variables/data-grid save (`PUT
   /api/scenario-data`, `PUT /api/scenario-vars`) regenerates
   `<file>.d.ts` next to the suite (`TypeDeclarations.java`) — an ambient
   TypeScript declaration for `execute()` and the suite's own global, with
   `.vars` keyed by every variable name currently known for that suite
   (its vars/data grid, every `${NAME}` placeholder actually referenced in a
   cell, and every `extract` step's output name), not just a generic
   string-indexed object. Written even for suites with no `.js` file yet —
   costs nothing and means the types are already waiting the moment you add
   one. VSCode's plain-JS "implicit project" mode picks up a `.d.ts` sitting
   next to a `.js` file automatically, no `jsconfig.json` needed. Hand edits
   to a `.d.ts` are overwritten on the next save — it's generated, not
   authored.

3. **A new `Flow` class, for anything the CSV model can't express** — write a `Flow`
   implementation (see `RunCommandFlow.java` for the per-row pattern, or
   `GenericStepFlow.java` for the grouped/multi-step + include pattern),
   register it in `FlowRegistry`, rebuild. The CSV columns come from
   `Flow.csvColumns()` and are entirely data from then on.

**Variables**: any cell in any flow's CSV may contain `${NAME}` — resolved
from `scenarios/<flow>/<file>.vars.csv` (name/value pairs) before the
row/case runs. Substitution happens after `include` expansion, so a suite
that includes another must also define any variables the included suite's
placeholders need — variables are **not** inherited automatically from
included files (see `full-signon-v2.vars.csv`, which redeclares
`USER`/`PASSWORD` on top of its own `COMMAND`/`EXPECTED_TITLE`). If this
bites people, the fix is to merge included files' `.vars.csv` too — not done
yet, deliberately kept simple for v1.

**The GUI's Variables panel edits `<file>.data.csv` directly** (`/api/
scenario-data` GET/PUT), not a plain name/value list: columns are variable
names, rows are saved value sets — "+ Variable" adds a column, "+ Row" adds
a value set, an "✕" on a column header removes that variable everywhere.
Saving (`DataDrivenRunner.saveGrid`) writes the whole grid to `.data.csv`
wholesale (not an append), derives the single "current" `.vars.csv` from the
**last** row (so a plain, non-data-driven Run All always substitutes
"whatever I saved most recently"), and regenerates the `.bat`/`.sh` pair.
`/api/scenario-vars` (plain name/value PUT, via `DataDrivenRunner.
onVarsSaved`/`appendRow`) still exists for API/back-compat — it only ever
appends one row — but the GUI itself no longer uses it.

**Data-driven re-run**: `scenarios/<flow>/<file>.data.csv` (columns = every
variable name ever saved, one row per data-driven run) has a `<file>.bat`
(Windows) + `<file>.sh` (Linux/macOS) pair next to it, regenerated on every
Variables-panel save. Run either one (or from CI) to replay the whole suite
once per row of `.data.csv`, each time with that row's `${NAME}` values
overriding the saved `.vars.csv` for that run only — a lightweight
data-driven test matrix built purely from "values I've actually saved and
tried," no separate authoring step. Both scripts are thin wrappers around
`run-suite --data-csv <path>` (see `Cli.runSuite`/`HelpText`'s `run-suite`
entry) - the actual per-row loop and CSV parsing live in Java (reusing
`Csv.java`), not duplicated in batch/shell. Edit `.data.csv` by hand (or
through the Variables panel) rather than the generated scripts, which are
overwritten on every save.

Scenario files live in `scenarios/<flow-name>/<file-name>.csv` (folder per
flow, multiple named files each — a real project explorer, not one fixed
file) — that folder holds only source/driver files (`.csv`, `.vars.csv`,
`.data.csv`, `.bat`/`.sh`), never generated run output. Results are written
to `results/<flow-name>/<file-name>.results.csv` (plus a timestamped copy)
after each run, in a top-level `results/` folder (sibling to `scenarios/`),
mirroring the flow/file structure; `extract` step dumps go the same way,
under a top-level `extracted/` folder (see above). Failing scenarios also
get a full screen-buffer dump at
`docs/samples/failures/<flow-name>/<file-name>/row-N.json` (cheap — ~2KB
each, capture liberally).

**Disconnect when done**: `/api/scenarios/run`'s `disconnectOnFinish` flag
(GUI: the "Disconnect when done" checkbox next to Headless; CLI:
`run-suite --disconnect-on-finish`) closes the session in a `finally` after
the run, regardless of PASS/FAIL or even a run-level error —
`SessionService.disconnect()`, the same close+deregister logic the manual
Disconnect button and RPC use. Off by default: a suite with no `disconnect`
step of its own should leave the session exactly as it left it, not have
one silently imposed.

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
