# five250 MCP server — live test report

Date: 2026-07-28. Target: **live pub400.com:23** (plain telnet) through the real
five250 daemon, driven end-to-end through a real stdio MCP client
(`test/client.mjs` speaking JSON-RPC: initialize → notifications/initialized →
tools/list → tools/call) against `server.mjs`.

Runner: `test/live-test.mjs`. Credentials came from `PUB400_USER`/`PUB400_PASS`
environment variables; the harness scrubs the password from all output and this
report uses `<PUB400_USER>` for the user profile.

## Result: **68 / 68 checks passed** (exit 0)

First run was 65/68; the 3 failures were harness expectation bugs, not server
bugs (expected "Pressed PF3" where the server correctly reports the name as
given, "F3"; and the SDK rejects an unknown tool with JSON-RPC error -32602
rather than the transport error the harness first expected). Fixed and re-run
clean.

## Coverage

Protocol: initialize handshake, tools/list (17 tools, all with schemas +
descriptions), unknown tool → `MCP error -32602: Tool no_such_tool not found`.

Negative paths (all returned `isError` tool results with actionable text):

| check | evidence (actual response excerpt) |
|---|---|
| screen_read before connect | `ERROR: No session 'default'. Call connect first. Use session_connect (then session_signon) before screen/record tools.` |
| bad AID key `F99` | `ERROR: Unknown key "F99". Valid AID keys: ENTER, F1-F24 (or PF1-PF24), PA1-PA3, PAGE_UP ...` |
| mark without recording | `ERROR: Not recording session 'default'. Call record-start first. Use record_start first, or check record_status.` |
| run nonexistent suite | `ERROR: No suite "custom-steps/definitely-not-real". Use suite_list to see what exists.` |
| bad flow | `ERROR: No such flow "nope".` |
| connect to bad host | `ERROR: Timed out connecting to no-such-host.invalid:23` |
| type with neither label nor row/col | `ERROR: Provide either label, or both row and col (see the fields table from screen_read).` |
| type at protected position (1,2) | `ERROR: No input field at row 1 col 2 ... use the fields table` |
| type at nonexistent label | `ERROR: No input field found near labels: Nonexistent Label XYZ ...` |
| extract bad target / row 99 / missing label | invalid-grammar, out-of-range, label-not-found errors |
| suite_save over existing file without overwrite | `ERROR: Suite "custom-steps/..." already exists. suite_get it, merge, then save with overwrite=true.` |
| run connect-less suite with no session | `ERROR: Suite "custom-steps/signon-common" has no 'connect' step and session "default" is not connected. session_connect first, or add a self-contained connect case to the suite.` |
| record_stop with flow but no file | `ERROR: Provide both flow and file to save, or neither to just view rows.` |
| bogus run id | daemon error surfaced as isError |

Positive paths, all live:

- `session_connect` pub400.com:23 → rendered welcome screen (excerpt):

```
Connected "default" to pub400.com:23.
screen 24x80 | cursor 5,25 | keyboard ready
   ....+....1....+....2....+....3....+....4....+....5....+....6....+....7....+....8
 1|         Welcome to PUB400.COM * your public IBM i server
 5|Your user name:
 6|Password (max. 128):
...
input fields (2) — use with screen_type:
  row  5 col 17 len  10 value="" label≈"Your user name:"
  row  6 col 22 len 128 value="" label≈"Password (max. 128):"
```

- Reconnect is idempotent ("already connected — not reconnecting").
- `session_signon` with env credentials → signed on (password never echoed);
  interstitial screens stepped past; command line (`===>`) reached.
- `screen_type` value=WRKACTJOB label="===>" then_press=ENTER → Work with
  Active Jobs; `screen_extract row:1` → `"Work with Active Jobs   PUB400"`;
  `label:Active jobs:` → live count; `rows:9-14:1-60` → 6 subfile lines.
- `screen_press F3` → back to `MAIN  IBM i Main Menu`; SIGNOFF returned to the
  entry screen.
- Recording: `record_start` → live sign-on + WRKACTJOB performed → `record_mark`
  check (row:1 contains "Active Jobs") + two extracts → F3 → recorded
  `disconnect` → `record_stop` returned exactly these rows (username shown as
  recorded; password value scrubbed here):

```
case,step,action,target,value,expected
signon-nav,1,type,label:Your user name,<PUB400_USER>,
signon-nav,2,type,label:Password (max. 128),****,
signon-nav,3,key,,ENTER,
signon-nav,4,type,label:===>,WRKACTJOB,
signon-nav,5,key,,ENTER,
signon-nav,6,check,row:1,,Active Jobs
signon-nav,7,extract,label:Active jobs:,active_jobs,
signon-nav,8,extract,rows:9-14:1-60,job_rows,
signon-nav,9,key,,F3,
signon-nav,10,disconnect,,,
```

- Parameterization: harness (playing the model's role) replaced the literals
  with `${USER}`/`${PASSWORD}`, prepended a self-contained `connect` case, and
  `suite_save`d it with **placeholder** vars (`your-pub400-user` /
  `your-pub400-password`). Verified via `suite_get` that no real credential was
  persisted anywhere.
- `suite_run` from a **cold, disconnected state** with real credentials passed
  only as per-run `vars` overrides:

```
run f1c2e5b7-... — status: done | scenarios: 1 (1 pass, 0 fail)
  [PASS] signon-nav
    extracted active_jobs: 794
    extracted job_rows:
       Opt  Subsystem/Job  User        Type  CPU %  Function
            #SYSLOAD       QSYS        SBS     0,0
              SYSLOAD      #SYSLOAD    ASJ     0,0  DLY-333
            QBATCH         QSYS        SBS     0,0
            ...
```

  (auto-connect fired, sign-on replayed via labels, check passed, both extracts
  landed — and the run summary contained no password.)
- `record_stop` direct-save path (`flow`+`file`) and discard path both verified;
  `record_status` correct in active and inactive states; `session_disconnect`
  and `session_list` verified.

## Cleanup

All suites/results/replays created by the tests (`mcp-live-*`, `mcp-rec-*`)
were deleted after the run; nothing containing a real credential was left on
disk by the tests or committed. (Note: some pre-existing user-created scenario
files in `scenarios/custom-steps/` contain literal values typed during earlier
manual GUI sessions — those predate this work, are untracked, and were left
untouched.)

## Not verified / known limitations

- JS-orchestrator suites were not executed through `suite_run` in this test run
  (the daemon's own demo exists; the MCP path is the same run endpoint).
- SSL connect (known broken upstream) was not exercised beyond defaulting off.
- `wait`, `if/loop` step authoring via `suite_save` is supported by the daemon
  and documented in the tool description, but the live test exercised the
  type/key/check/extract/include/connect/disconnect actions only.
- Daemon auto-start: the live run used an already-running daemon, so the
  successful-spawn path was not exercised end-to-end. The unreachable/spawn-fail
  branch WAS verified live (`FIVE250_URL` pointed at a dead port with a bogus
  jar): the server attempted the spawn, waited 10s, and returned
  `ERROR: five250 daemon is not reachable at http://127.0.0.1:25299 and
  auto-start failed. Start it manually: FIVE250_HOME="..." java -jar "..." serve`.
