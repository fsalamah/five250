# five250

A self-contained 5250 (IBM i / AS400) terminal automation tool. It drives a
live [tn5250j](http://tn5250j.sourceforge.net/) session headlessly and
exposes the screen as **plain structured data** — text buffer, cursor,
keyboard/inhibit state, and the input field table — never pixels.

## What's in here

- **CLI + daemon** — `five250 connect/signon/type/key/screen/fields/...`
  against a real IBM i host (tested against [pub400.com](https://pub400.com)).
- **Local web GUI** (`http://127.0.0.1:25251`) — a Terminal tab for manual
  exploration (with CL command autocomplete) and a Scenarios tab for
  building/running automated test suites.
- **CSV-only scenario engine** — entire automation flows authored as pure
  data, no code: `case,step,action,target,value,expected` rows support
  `type`, `key`, `check`, `extract` (pull structured data off the screen),
  `include` (reuse another suite), `connect` (self-contained suites), and
  `wait`. Supports `${VAR}` parametrization via per-file `.vars.csv` files.
- **Record & playback** — record a live session by driving the Terminal tab
  (or CLI) and save it straight into a runnable scenario CSV.
- **Replay viewer** — every scenario run captures a full screen snapshot at
  every step, viewable afterward in the GUI.
- **CI-runnable** — `five250 run-suite --flow F --file N [--var NAME=VALUE]`
  runs a suite headlessly and exits 0/1/2/3 (pass/fail/error/timeout).

See [`CLAUDE.md`](CLAUDE.md) for the full architecture, conventions, and
authoring rules used while building this.

## Build & run

```
mvn package
java -jar target/five250.jar help
```

The package is self-contained: `scenarios/` and `docs/` resolve relative to
wherever `five250.jar` lives (override with `FIVE250_HOME`), so the jar plus
`bin/`, `scenarios/`, and this repo's other top-level files can be copied
anywhere and run unchanged.

## Credentials

Never commit real credentials. Suites reference `${USER}`/`${PASSWORD}`
style variables backed by per-file `.vars.csv` files — the demo `.vars.csv`
files in this repo ship with placeholder values only; edit them locally with
your own PUB400 (or other IBM i host) credentials before running.
