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

**Package is self-contained** (`Home.java`): the app itself (`web/`,
`cl-commands.json`, the `projects.json` registry) resolves relative to the
running jar's own directory, not the current working directory (override
with `FIVE250_HOME` if you want that elsewhere). Copy `five250.jar` +
`bin/` + `five250-completion.bash` + `CLAUDE.md` anywhere and it works
unchanged — verified live by running it from a completely unrelated
directory.

**No-Java-required distribution** (`target/dist/` after `mvn verify` —
**not** `mvn package`; the antrun execution that builds it is bound to
the `verify` phase specifically so it always runs after both `shade` and
a release build's `proguard` step regardless of plugin declaration order
— `mvn package` alone stops one phase too early and silently leaves
`target/dist/` stale or missing). A `maven-antrun-plugin` execution
`jlink`s a trimmed custom JRE (just the modules this app
actually needs — `java.base,java.desktop,java.prefs,java.sql,java.logging,
java.naming,jdk.httpserver,jdk.internal.vm.ci,jdk.jfr,jdk.management,
jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki` — the bulk from `jdeps
--print-module-deps` against the shaded jar, plus a few added by hand for
GraalVM/Truffle's own reflective needs jdeps' static analysis can't see)
into `target/dist/runtime/`, sits `five250.jar` next to it, and copies in
both launcher scripts (`packaging/five250.bat`, `packaging/five250.sh` —
`java -jar` against `runtime\bin\java(.exe)` next to the script, never
whatever Java happens to be on the machine's PATH) plus `CLAUDE.md` and
the bash completion script. Copy `target/dist/` anywhere on a machine of
the **same OS/architecture it was built on** and it runs with zero Java
install — verified live: killed every `java` process on this machine,
copied `target/dist/` to an unrelated temp directory, ran `five250.sh`
from there, confirmed via `Get-Process` that the resulting daemon's
`java.exe` resolved to that copy's own `runtime\bin\`, then ran a real
script (GraalJS `importSuite`/`execute`, a live socket connect to
pub400.com, `console.log`) through it end-to-end successfully.

`jlink` needs a FULL JDK (one with a `jmods/` directory) on `JAVA_HOME`
when you run `mvn package` — a JRE-only or already-`jlink`'d custom
runtime can't `jlink` a new one from itself (this bit in practice: this
machine's default `JAVA_HOME` at one point pointed at a Katalon-bundled
runtime with no `jmods/`); the antrun target fails fast with a clear
message naming `${java.home}` if that directory's missing, rather than
failing deep inside the `jlink` invocation.

`jlink` itself is NOT cross-compiling — it only ever produces a runtime
for the OS/architecture of the JDK you run it from. There's no single
build that produces all three; `target/dist/` from a Windows `mvn
verify` only runs on Windows, and the identical Maven goal needs to run
again on a real macOS box and a real Linux box to produce their own
`target/dist/` (this is why `packaging/five250.sh` exists as a companion
to `five250.bat` — same launcher logic, POSIX syntax — even though only
the Windows side has actually been built and run in this repo so far).

**Obfuscation (`mvn verify -Prelease`) — confirmed non-functional, do
not use.** A `-Prelease` Maven profile exists (`pom.xml`,
`proguard.pro`) that renames only `com.acabes.five250.*` identifiers via
ProGuard — the most conservative mode available, everything else
(GraalVM/Truffle, tn5250j, the JDK) explicitly kept untouched, chosen
specifically to avoid breaking reflection-heavy code. It still breaks
it: the resulting jar builds successfully but throws `Only one
implementation of APIAccess allowed.
org.graalvm.polyglot.Engine$APIAccessImpl` the instant it runs any
GraalJS code — i.e. every suite/script run. Confirmed live via two
independent approaches (the `com.github.wvengen` wrapper plugin, then
driving `proguard.ProGuard` directly with only JDK jmods as
`-libraryjars`, no project dependencies at all going into ProGuard) —
same failure both times, and the shaded jar has no duplicate
class/service-file entries going in (checked with `unzip -l`), so
ProGuard's own zip-processing is introducing something Truffle's engine
bootstrap treats as a second registration, not chased down further. See
the `-Prelease` profile's own comment block in `pom.xml` for the full
finding. **A real release build is just `mvn clean verify` — no
`-Prelease`.** Stop the daemon (or anything else holding
`target/five250.jar` open) before `mvn clean ...` on Windows, or the
clean step fails outright ("Failed to delete ...five250.jar") rather
than silently leaving a stale jar.

**Project workspaces** (`ProjectRegistry.java`): all real data — `suites/`,
`scripts/`, `results/`, `extracted/`, `docs/samples/` — lives under a
*project* root, not directly under `Home.DIR`. Exactly one project is active
in the daemon at a time (switch it in the GUI's titlebar, or `five250
project open <name>`); every API call and CLI command reads/writes whichever
project is currently active unless a call explicitly pins one (`run-suite
--project <name>`, CI-safe — doesn't depend on or change whatever the GUI
has open). `five250 project list|create|open|current` manages the registry.
A fresh install with no project yet shows an empty "create one" state in the
GUI; an existing pre-project install gets its `scenarios/` (now `suites/`)
auto-adopted as a project named `"default"` the first time the registry
loads — zero manual migration.

**Dev-tree gotcha, learned the hard way**: running `java -jar target/five250.jar
...` (the natural thing to do after `mvn package`, straight from the source
tree) means `Home.DIR` resolves to `target/`, NOT the project root — so the
daemon silently reads and writes `target/projects.json` (and adopts/creates
projects under `target/`), a build artifact directory `mvn clean` deletes,
completely separate from the git-tracked `suites/` next to `pom.xml`.
`Csv.read()` returns an empty list for a missing file with no error, so a
suite silently runs "0 scenarios" instead of failing loudly — and worse,
anything recorded live (via the GUI's Record feature) lands in a
`target/`-rooted project, invisible to git, at risk of being deleted by the
next clean build. When iterating in the dev tree, always launch with
`FIVE250_HOME=<project root> java -jar target/five250.jar ...`, or copy the
built jar up to the project root first (matching how the actual packaged
distribution is laid out — jar and its projects as siblings).

**Rebuilding the jar while the daemon is up** (`Home.jarStaleReason`): the
daemon loads classes lazily from `five250.jar` and stays up across commands,
and on Windows `mvn package` silently rewrites `target/five250.jar` in place
underneath it. The next class it hasn't loaded yet then comes out of a zip
that no longer matches what its classloader indexed - and for a script run
that class is GraalJS's `Engine$ImplHolder`, whose static initializer fails
once and then reports `Could not initialize class
org.graalvm.polyglot.Engine$ImplHolder` on every run after that, with no
hint that a rebuild caused it (reproduced live exactly this way). The daemon
now stamps its jar's size+mtime at startup: once they change, the TCP
protocol, `/api/scenarios/run` and `/api/scripts/run` refuse with a message
naming both build times and saying to restart (`ping`/`shutdown` still
work), and the CLI's `ensureDaemonRunning` sees `stale:true` in the ping
reply and transparently shuts the old daemon down and starts a fresh one
(dropping live sessions - it prints a one-line notice). The GUI can't restart
the daemon for you: after a rebuild, run any `five250` command (or relaunch
`java -jar five250.jar`) before clicking Run again.

**CI / headless**: `five250 run-suite --flow F --file N [--var NAME=VALUE ...]`
drives a suite exactly like clicking Run All, prints each step + PASS/FAIL,
and exits 0/1/2/3 (pass/fail/run-error/timeout) — a real CI gate. `--var`
overrides that file's saved `.vars.csv` values for this run only.

## Connection

- Use **plain telnet, port 23** (`--host pub400.com --port 23`). SSL (`--ssl`,
  port 992) currently hangs during the TLS handshake in this tn5250j build —
  do not use it until that's debugged.
- **Host code page** (`Terminal.resolveCodePage`): `connect --codepage <ccsid>`
  sets the EBCDIC code page used to decode every screen and encode everything
  typed. Default is the `FIVE250_CODEPAGE` env var if set, else CCSID 37 (US
  English) - so a host in any other code page shows as garbage accented Latin
  until this matches it (an Arabic CCSID 420 host read as 37 turns `رقم` into
  `ÍÝ]`). Every connect path takes it: CLI `--codepage 420`, the GUI's "Code
  page" box, a suite `connect` row's `expected` cell (`codepage=420`, or
  `true;codepage=420` with SSL), a script's `connect(host, port, ssl, 420)`,
  and RPC `"codepage"`. tn5250j has built-in tables for a fixed list (37, 273,
  277, 278, 280, 284, 285, 297, 424, 500, 870, 871, 875, 1025, 1026, 1112,
  1140, 1141, 1147, 1148); anything else, Arabic 420 included, goes through
  the JDK charset of that number, which lives in the `jdk.charsets` module -
  on the jlink module list in `pom.xml` for exactly this reason. tn5250j
  silently falls back to 37 for a name it can't resolve, so `resolveCodePage`
  rejects an unknown one up front with a clear error instead. Verified against
  a local fake 5250 host sending CCSID 420 bytes (decode, typing Arabic back,
  suite, script, env default, through the jlink'd dist) - NOT yet against a
  real Arabic IBM i; PUB400 can't exercise it.
- **Right-to-left text** (`Bidi.java`): a 5250 screen holds Arabic/Hebrew in
  VISUAL order - one character per cell, left to right as displayed, so a
  word sits in the buffer back to front. Anything that is a picture of the
  screen stays in cell order and must not be reordered: the GUI grids turn
  the browser's bidi off (`unicode-bidi: bidi-override`, each non-Latin
  character in its own 1ch `.gc` cell) - without that the browser reverses
  the run a second time, which is the "Arabic appears reversed" symptom -
  and screenshots, `five250 screen`, `screen --json` and replay snapshots are
  raw cells too. Anything that is DATA taken off the screen is converted to
  reading (logical) order: `check`/`extract` values (`row:`, `rows:`,
  `table:`, `label:`, `message`), therefore a script's `.vars`, `console.log`,
  `saveJson`, and the results/extracted files. So a `check` row's `expected`
  and a `label:` target are written normally, as you would type them. Column
  ranges are applied to cells first, converted second. The conversion
  reverses each right-to-left run and restores numbers inside it; it is its
  own inverse and is not the full Unicode bidi algorithm. `FIVE250_BIDI=off`
  disables it everywhere. Verified against the fake host sending visual-order
  CCSID 420 text; same for a script and a headless run (it lives in
  `StepActions`, below the GUI). NOT handled: typing - a `type` value is sent
  in the order written, so an Arabic value lands in cells in that order; and
  letter joining - Arabic is shown one unjoined letter per cell.
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

## GUI + suite engine

The daemon also serves a local web GUI at **http://127.0.0.1:25251** (starts
automatically alongside the TCP protocol on 25250 — same `java -jar
target/five250.jar connect ...` call brings both up). Three tabs:

- **Terminal** — connect/signon, live screen view, type-into-field (with CL
  command autocomplete + an IDE-style docs panel, sourced from
  `web/cl-commands.json`), and an AID-key keypad. Human-usable version of the
  CLI, for manual exploration when mapping a new screen.
- **Suites** — a project explorer for plain CSV suites (see #1 below): CRUD
  files under `suites/<flow>/` (create/rename/delete), CRUD rows within a
  file, edit its **Variables** panel, and **Run All** — runs asynchronously
  with a live progress panel: scenario count, the exact step currently
  executing, and (unless Headless is checked) the live 5250 screen updating
  in near-real-time via polling. Before running, checks the target session
  is actually connected and refuses with a clear message instead of a raw
  "no session" error if not. There's only ever one `Flow` implementation
  registered (`custom-steps`/`GenericStepFlow`) — no flow picker, since a
  second one (`RunCommandFlow`, a narrow single-CL-command shortcut) turned
  out to be entirely subsumed by it and was removed as a confusing,
  redundant filter.
- **Scripts** — standalone `.js` orchestrators (see #2 below), fully
  decoupled from any one suite: CRUD files under `scripts/<name>.js`
  (a new one starts from a template that `connect()`s its own session and
  `disconnect()`s it in a `finally`, self-contained by default — see #2), a
  Monaco editor with a **+ Import Suite** picker (lists every real suite on
  disk, inserts the exact `importSuite(flow, file)` call for it) and a live
  "declared imports" line parsed from the script's own source, **Run**, and
  the same Running/Results/Replay panel the Suites tab uses.

### Three ways to add automation

1. **Pure CSV, no code (`custom-steps` flow)** — this is the one to reach for
   first. Each scenario is a group of rows sharing a `case` id, executed in
   `step` order:
   `case, step, id, action(type|key|check|extract|screenshot|include|connect|wait|disconnect), target, value, expected`.
   `id` is optional and blank on most rows — freeform, unique across the
   whole file — its only use is letting a `project/scripts/*.js` script
   address a range by name instead of raw row position (see `.steps(a, b)`
   under #2 below); `custom-steps` itself ignores it. Uniqueness is
   enforced, not just documented: saving a suite with two rows sharing a
   non-blank id (the Suites tab's Save button, `PUT /api/scenarios`) is
   rejected outright with a clear error naming the id and both row numbers
   (`StepIds.validateUnique`) — surfaced as a toast in the GUI, not a silent
   write. `importSuite()` re-checks the same rule as a safety net for a file
   that reached disk some other way (hand-edited, copied in).
   `disconnect` closes the session outright - recorded automatically when the
   live session actually disconnects, so replay reaches the same end state
   the recording did (no lingering connection the live run never had).
   `type` target is `label:<text>` (preferred) or `<row>,<col>`; `check`/
   `extract` target is `message`, `row:<n>`, or `label:<text>`; `include`
   target is another CSV file name (no `.csv`) in the same flow folder — its
   steps are spliced in at that point, so one suite can reuse another
   (cycle-checked; see `suites/custom-steps/signon-common.csv` +
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
   the results CSV at all. See `suites/custom-steps/extract-demo.csv` —
   pulls active job count, CPU%, and elapsed time off a protected line of
   WRKACTJOB.

   `connect` makes a suite fully self-contained — no prior manual Connect
   click needed. Put it alone in its own case (target=host, value=port,
   expected="true" for SSL); `HttpApi.autoConnectIfNeeded()` intercepts it
   before the run starts, connects only if that session doesn't already
   exist, then strips the whole pseudo-case either way. See
   `suites/custom-steps/self-contained-signon.csv` — runs correctly from
   a completely cold, zero-sessions daemon state, verified live. Careful:
   re-running an unconditional-signon suite on an *already* signed-on
   session will fail (the sign-on fields won't exist wherever it lands) —
   that's correct behavior, not a bug: `connect` only ever runs once, before
   the suite starts, regardless of `if`/`loop` branching inside the suite
   itself.

   `wait` (value = seconds, 0-120 capped) is a deliberate, opt-in exception
   to "never sleep" — use it only for delays outside the 5250 buffer
   (a batch job finishing) that `waitReady()`'s keyboard/buffer-stability
   polling can't detect. See `suites/custom-steps/wait-test.csv`.

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
   hanging. See `suites/custom-steps/if-loop-demo.csv` for a worked
   example — self-contained (its own `connect` + `include signon-common`, so
   it opens and runs cleanly in the GUI from a cold session), demonstrating
   `if`/`else` on an extracted title, `loop while` (a real screen value
   naturally converging), and `loop count`.

2. **A standalone `project/scripts/<name>.js`, for real JS control flow
   instead of the `if`/`loop` mini-language** — fully decoupled from any one
   suite (`JsSuiteRunner`): a script has no CSV of its own, only whichever
   suites it explicitly pulls in with a global `importSuite(flow, file)`
   function. That call loads `suites/<flow>/<file>.csv` (+ its own sibling
   `<file>.vars.csv`) fresh off disk and returns a suite object exposing:
   - `.vars` — a live, two-way bound object over that suite's OWN vars map
     (never shared with a different imported suite): `mySuite.vars.username
     = 'abc'` before an `execute()` call feeds that value into substitution
     for that call; an `extract` step inside an executed range writes its
     result back into this same map (`StepActions.executeAction`), so
     `console.log(mySuite.vars.transactionAmount)` right after reads it
     back. Substitution is NOT one-shot up front — it happens fresh on every
     `execute()` call, against whatever's currently in `.vars`.
   - `.steps(a, b)` — a range over that suite's rows. Each of `a`/`b` is
     either a 1-based row position in its own CSV file (matching what its
     Steps table shows in the Suites tab) or a string naming that row's own
     `id` cell — the CSV's optional `id` column (see the `custom-steps`
     columns list above), blank on most rows, set only where a script needs
     to name a boundary: `mySuite.steps("login", "after-login")`. Not
     `case`/`step` column values either way. Mixing a number and a string
     argument is fine. An `id` must be unique across the whole file —
     `importSuite()` throws immediately if it isn't, instead of `steps()`
     silently resolving to the wrong row later.
   - `.all()` — shorthand for `.steps(1, <row count>)`, the common "just run
     this whole suite" case.
   A global `execute(range)` function runs any range — from any imported
   suite — against the live terminal right now, in order; the range itself
   carries which suite (and which vars map) it came from, so `execute()`
   never needs to be told which suite is "current."

   This means real `for`/`while`/functions/`try`/`throw` sequencing over
   however many imported suites a script needs, instead of learning
   `if`/`else`/`endif`/`loop`/`endloop`. Sandboxed with `HostAccess.EXPLICIT`
   (only `importSuite`/`execute`/`console` are reachable, no arbitrary Java
   classes) rather than `JsCondition`'s full no-host-access sandbox, since
   the script has to call back into Java to drive the terminal at all — a
   deliberately bigger bridge for a deliberately more powerful authoring
   mode. A whole-script wall-clock timeout (5 minutes) is enforced by
   force-cancelling the GraalJS `Context` from the calling thread
   (`context.close(true)`) if exceeded — verified to actually stop a running
   script, not just abandon it.

   A script opens its own session with the `connect(host, port?, ssl?)`
   global (a no-op if that session's already live — same underlying call a
   suite's `connect` row or the Terminal tab's Connect button makes) and
   closes it with `disconnect()`. `POST /api/scripts`'s new-script template
   always opens with `connect(...)` and closes with `disconnect()` in a
   `finally`, so a script is self-contained by default — pass, fail, or
   thrown error, its session doesn't leak. Both are plain function calls,
   not enforced: delete either from a script that should share an
   already-connected session (or leave one open for something after it)
   instead. The template's body is commented-out example code, not live —
   it shows two things concretely rather than describing them in prose:
   logging in (`importSuite('custom-steps', 'signon-common')`, set
   `.vars.USER`/`.vars.PASSWORD` before `execute(signon.all())` —
   `signon-common.csv` is a small reusable suite: type user, type password,
   ENTER, ENTER) and carrying a value between two different imported
   suites' vars (`mySuite.vars.SOME_VAR = signon.vars.USER` — each
   `importSuite()` call gets its own `.vars` map, never shared, so a
   hand-off like this is always explicit, never automatic). Both examples
   were run for real (uncommented, live against pub400.com) before being
   written into the template, including asserting the vars isolation and
   hand-off actually behave as claimed — not just written and assumed
   correct. `execute()` resolves the live Terminal fresh from
   `SessionService` on every call rather than once up front, since
   `connect()` may not have run yet when the script's `.js` was first
   evaluated — it throws a clear "call connect() first" style error if
   there's still no session when a range actually needs to run.

   Limitation: `include`/`connect` **rows** (the CSV `action=connect`
   pseudo-case, distinct from the `connect()` JS global above) aren't
   executable via `execute()` — they're resolved by the surrounding pipeline
   before a plain suite run starts, not inside an executed range — so
   importing a suite that relies on CSV-level `include`, or trying to
   `execute()` a range containing its `connect` row, will throw. See
   `scripts/js_orchestrator_demo.js` for a worked example: imports
   `custom-steps/js_orchestrator_demo`, sets a var before `execute()`, reads
   one back after an `extract`, and uses a real JS `for` loop calling
   `execute()` repeatedly — the JS-native analog of `loop count`.

   **Saving a script's own data** — `saveJson(name, data)` and
   `saveCsv(name, rows)` write whatever a script computes to
   `extracted/scripts/<script name>.<name>.json` (or `.csv`), overwriting
   on every call — separate from the automatic per-run results/extracted
   dump every run already gets (that's for a suite's own `extract` steps;
   this is for anything else a script wants to persist, e.g. a summary
   object it built up across several imported suites). `data` for
   `saveJson` can be any JSON-shaped JS value — object, array, string,
   number, boolean, `null`; `rows` for `saveCsv` is an array of flat
   objects, columns taken from the first row's own keys, same convention
   `Csv.write` already uses elsewhere. `name` is sanitized the same way a
   suite/script file name is (`HttpApi.safeName`) so it can't escape
   `extracted/scripts/` via `../` or a path separator — verified live: a
   script that tried `saveJson('../../evil', ...)` landed its file safely
   inside `extracted/scripts/`, not outside it. See
   `scripts/save-data-demo.js` for a worked example — imports
   `custom-steps/js_orchestrator_demo`, `execute()`s its login range, then
   `saveJson`s a summary object and `saveCsv`s a couple of observation
   rows; run for real against pub400.com and both files' exact on-disk
   content confirmed to match what the script computed before being
   written into this doc.

   **Passing arguments into a script from the command line** — `five250
   run-script <name> --var NAME=VALUE ...` (repeatable, same flag/parser
   as `run-suite --var`) becomes that run's `args.NAME` inside the
   script, via the read-only `args` global — a script has no suite of
   its own, so this is NOT the same channel as a suite's own
   `${NAME}`/`.vars.csv` substitution; it's how a `.bat`/`.sh` that calls
   `five250 run-script ...` (or a CI job) hands the script values from
   outside its own source — which host to hit, which command to type —
   without editing the script file itself every time.
   `args.SOME_MISSING_KEY` reads as `undefined`, not an error, so a
   script can fall back with `args.HOST || 'pub400.com'` to also run
   fine with no `--var` at all. Same async start/poll/print CLI pattern
   as `run-suite` (`Cli.runScript`), including streaming the script's
   `console.log`/`console.error` output as it happens, not just at the
   end. See `scripts/cli-args-demo.js` for a worked example — verified
   live three separate ways: `java -jar target/five250.jar run-script
   cli-args-demo --var HOST=pub400.com --var COMMAND=WRKSPLF` (args
   flowed through correctly), the same command with no `--var` at all
   (fell back to its defaults correctly), and — the actual point of
   building this — through the real jlink'd distribution's own
   `target/dist/five250.bat run-script cli-args-demo --var HOST=... `
   `--var COMMAND=...`, confirming a real batch file really can pass
   arguments into a script as variables end to end, not just through
   the daemon's HTTP API directly.

   **Every script gets its own `<name>.bat`/`<name>.sh` pair for free**
   (`ScriptBatchFiles.java`) — written next to it in `scripts/` the
   moment it's created (`POST /api/scripts`), self-healed on every
   Scripts-tab load for a script that predates this feature (`GET
   /api/scripts`), and removed alongside it on delete. Both are thin
   wrappers: `<name>.bat --var HOST=pub400.com --var COMMAND=WRKACTJOB`
   (or `./<name>.sh ...`) forwards every flag straight through to
   `run-script <name> ...` untouched — unlike a suite's generated
   `.bat`/`.sh` (`DataDrivenRunner`), which defaults to looping a saved
   `.data.csv` when called with no flags, a script has no such saved
   default variable set, so there's nothing to loop and no branching
   needed. The jar path embedded inside is `Home.file("five250.jar")`'s
   **absolute** path, resolved once at generation time, deliberately not
   a `../..`-style relative climb from the script's own directory —
   `scripts/` sits at a different depth below the jar depending on
   whether the active project is `"default"` (project root == the jar's
   own directory) or any other project (`Home.DIR/projects/<name>`, one
   level deeper), so a single fixed relative path can't be correct for
   both; an absolute path sidesteps that distinction entirely. Verified
   live: ran a generated `sc1.bat` directly (no `five250 run-script`
   typed anywhere) and confirmed it drove a real pub400.com session
   through to PASS, then ran `cli-args-demo.bat --var HOST=... --var
   COMMAND=...` and confirmed both values reached `args.HOST`/
   `args.COMMAND` inside the script exactly as `five250 run-script`
   itself would have delivered them.

   **Monaco editor for scripts, right in the GUI** — the Scripts tab has a
   real Monaco editor (the editor VSCode itself is built on, not a
   re-implementation) with **Save**, **Run**, and **+ Import Suite** (opens
   a picker over `GET /api/suites` — every real suite on disk right now —
   and inserts the exact `const x = importSuite("flow","file");` line at
   the cursor, so importing is a click, not something you have to know to
   type). A live "declared imports" line above the editor re-parses the
   script's own source for `importSuite(...)` calls on every keystroke,
   mirroring the same regex `GET /api/scripts/source` uses server-side, so
   "what does this script depend on" is visible without reading the code.
   `GET/PUT /api/scripts/source?name=` serves the source, a fixed ambient
   `.d.ts` (`HttpApi.SCRIPT_DECLARATIONS` — `importSuite`/`Suite`/`execute`
   types; the same shape for every script, since `importSuite`'s return
   type never varies, so nothing needs regenerating per script or written
   to disk) fed straight to Monaco's `addExtraLib()`, and the parsed
   `imports` list. Monaco is vendored locally under
   `web/vendor/monaco-editor/` (`npm install monaco-editor`, copy `min/vs/`
   in) and loaded lazily via its own AMD `loader.js` only the first time the
   Scripts tab is actually used — never CDN-fetched, matching how everything
   else in this GUI is self-hosted. It's a real, meaningful size addition
   (~12MB) to the jar, worth knowing about if that ever matters for
   distribution.

   **Live console output + real screen while a script runs** — the
   Scripts tab's bottom panel has a dedicated **Console** tab alongside
   Running/Results, both updating on the same ~700ms poll a suite run's
   Running panel uses: `console.log`/`console.error` output
   (`RunTracker.RunState.console`, appended to live by `JsSuiteRunner`'s
   bound `console` object via an `onLog` callback threaded through `run()`
   — not just visible in the daemon's own stdout after the fact, and not
   mixed into the same view as the live screen — a script that logs a lot
   doesn't crowd out the screen, and vice versa) and, in the Running tab,
   the actual AS/400 screen rendered the same way the Terminal tab renders
   it (`screenGridHtml()` — field/cursor-highlighted HTML, not a flat text
   dump). `console.error` lines get a distinct color. `console.log`'s
   string formatting is worth knowing about: `Value.toString()` on a
   GraalJS value is a debug-only representation, NOT guaranteed to return
   a guest string's actual content — confirmed the hard way, it printed
   the literal class name `com.oracle.truffle.api.strings.TruffleString`
   instead of the logged text until `JsSuiteRunner.stringifyForConsole`
   started extracting each primitive through its own `Value.as...()`
   accessor (`asString()`/`asBoolean()`/`asLong()`/`asDouble()`) instead
   of relying on `toString()`.

   **Connected/disconnected is always visible, not just near the Run
   button** — both the Suites and Scripts tabs show the session badge
   (`● <sid> connected` / `○ <sid> not connected`) in two places at once,
   driven by one shared poll so they can't drift out of sync
   (`applySessionBadge()` in `index.html`): the top toolbar (as before)
   and now also the Running tab itself, next to the "current step" line —
   the place you're actually looking while watching a run, useful
   especially for a self-connecting script, where the badge visibly flips
   from disconnected to connected the moment its own `connect()` call
   lands, and back once `disconnect()` runs in its `finally`. Verified
   live via Playwright: the Running-tab badge showed `● default
   connected` mid-run and `○ default not connected` both before the run
   started and after it finished.

3. **A new `Flow` class, for anything the CSV model can't express** — write a
   `Flow` implementation (see `GenericStepFlow.java` for the grouped/
   multi-step + include pattern this project actually uses), register it in
   `FlowRegistry`, rebuild. The CSV columns come from `Flow.csvColumns()` and
   are entirely data from then on. (A second implementation,
   `RunCommandFlow` — one CL command + expected title per row — used to ship
   alongside `custom-steps` but was removed: it was entirely subsumed by
   `custom-steps`'s general step DSL, and having two `Flow`s made the
   now-gone Suites-tab flow picker read as a confusing filter rather than a
   real choice. Its few existing suites were converted to plain
   `custom-steps` CSVs instead of deleted.)

**Variables**: any cell in any flow's CSV may contain `${NAME}` — resolved
from `suites/<flow>/<file>.vars.csv` (name/value pairs) before the
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

**Data-driven re-run**: `suites/<flow>/<file>.data.csv` (columns = every
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

Suite files live in `suites/<flow-name>/<file-name>.csv`, under the current
project's root (folder per flow, multiple named files each — a real
explorer, not one fixed file) — that folder holds only source/driver files
(`.csv`, `.vars.csv`, `.data.csv`, `.bat`/`.sh`), never generated run output.
Standalone scripts live in a separate, flat `scripts/<name>.js` (no per-flow
folder — a script isn't tied to any one flow's CSV schema). Results are
written to `results/<flow-name>/<file-name>.results.csv` (plus a timestamped
copy) after each run, in a top-level `results/` folder (sibling to
`suites/`), mirroring the flow/file structure; a standalone script's run
writes under a `results/scripts/<name>...` pseudo-flow bucket instead, same
mechanism (`HttpApi.writeRunArtifacts`) — either way it's just one
`ScenarioResult` list to write out identically. `extract` step dumps go the
same way, under a top-level `extracted/` folder (see above). Failing
scenarios also get a full screen-buffer dump at
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
`GenericStepFlow.runGroup()`, which calls `result.step(label, t.snapshot())`
after each meaningful action (`JsSuiteRunner`'s `execute()` does the same for
a script run, via the shared `StepActions.executeAction`). Persisted
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

## Screenshots

`ScreenImage.png(snapshot)` draws a `Terminal.snapshot()` map to a
terminal-style PNG: character grid, input fields underlined, cursor block,
and a status line (size, cursor, keyboard state). There is no terminal window
anywhere in this stack to capture, so it is always rendered off-screen into a
`BufferedImage` from the buffer - which is why it works the same with no
browser open, in a GUI "Headless" run, from the CLI/CI, and under
`java.awt.headless=true` (verified by forcing that flag on the daemon).
Four ways in, one renderer:

- CLI: `five250 screenshot [--out file.png] [--session id]` - `--out` is
  relative to the caller's directory; default is
  `screenshots/<session>.<timestamp>.png` in the active project.
- Suite step: `action=screenshot`, `value` = image name (default `step<N>`).
- Script: `screenshot(name?)`.
- GUI: the Terminal tab's Screenshot button (`GET /api/screenshot?sessionId=`
  returns `image/png`; `download=1` adds a file name).

Suite and script captures are held on the `ScenarioResult` and written with
the rest of the run's artifacts (`HttpApi.writeRunArtifacts`) to
`screenshots/<flow>/<file>.<name>.png` plus a timestamped copy
(`screenshots/scripts/<script>.<name>.png` for a script), same naming scheme
as `extracted/`. Each character is drawn in its own cell so columns stay
aligned whatever font a glyph falls back to; the cost is that Arabic letters
are drawn unjoined, in host cell order. No colors/attributes - the buffer
doesn't expose them (see Known limitations).

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
