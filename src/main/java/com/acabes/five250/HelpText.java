package com.acabes.five250;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full per-command documentation for `five250 help [command]`. */
public final class HelpText {

    private HelpText() {}

    record Entry(String summary, String usage, String description, List<String> options, List<String> examples) {}

    static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        ENTRIES.put("serve", new Entry(
            "Start the daemon + GUI, no host/session needed",
            "five250 serve",
            "Starts the background daemon (TCP 25250 + HTTP/GUI 25251) if it isn't already running "
                + "and exits immediately - no connection, no session. For driving everything "
                + "(Connect included) from the browser instead of the CLI. Running the jar with no "
                + "arguments at all does the same thing, then also shows this command list.",
            List.of(),
            List.of("five250 serve", "java -jar five250.jar")
        ));

        ENTRIES.put("connect", new Entry(
            "Open a live 5250 session to an IBM i host",
            "five250 connect --host <host> [--port <port>] [--ssl] [--wide] [--codepage <ccsid>] [--session <id>]",
            "Opens a socket to the host, negotiates the 5250 data stream, and waits until the "
                + "first screen (usually sign-on) is fully painted and the keyboard unlocks. "
                + "Starts the background daemon automatically on first use - it holds the live "
                + "session between CLI calls, so every subsequent command against the same "
                + "--session id talks to the same connection.",
            List.of(
                "--host      Hostname or IP of the IBM i system (required)",
                "--port      Telnet port. Default 23, or 992 if --ssl is given",
                "--ssl       Use SSL/TLS. Known issue: hangs mid-handshake in this build - use plain port 23",
                "--wide      Request the 27x132 extended screen size instead of 24x80 - the host has to support/grant it too; silently stays 24x80 if it doesn't",
                "--codepage  Host code page (EBCDIC CCSID) used to decode screens and encode typed text, e.g. 420 for Arabic, 424 for Hebrew. Default: the FIVE250_CODEPAGE env var if set, else 37 (US English). A host in another code page shows as garbage accented letters until this matches it",
                "--session   Session id, for running multiple concurrent sessions. Default 'default'"
            ),
            List.of(
                "five250 connect --host pub400.com --port 23",
                "five250 connect --host myibmi.example.com --codepage 420",
                "five250 connect --host pub400.com --session second"
            )
        ));

        ENTRIES.put("signon", new Entry(
            "Sign on with a user/password on the current sign-on screen",
            "five250 signon --user <user> --pass <pass> [--session <id>]",
            "Label-anchors the username and password fields on the current screen (matches "
                + "labels like \"User\", \"User ID\", or \"Your user name:\"), types the given "
                + "values, presses Enter, and waits for the resulting screen to settle. Does not "
                + "handle a forced password-change screen automatically - check the screen after "
                + "and drive it manually with `type`/`key` if one appears.",
            List.of(
                "--user      User profile name",
                "--pass      Password. Prefer an env var over a literal on the command line: "
                    + "five250 signon --user \"$USER\" --pass \"$PASS\"",
                "--session   Session id to sign on. Default 'default'"
            ),
            List.of("five250 signon --user FSALAMAH --pass \"$PUB400_PASS\"")
        ));

        ENTRIES.put("screen", new Entry(
            "Print the current screen buffer",
            "five250 screen [--json] [--session <id>]",
            "Dumps the live 24x80 (or negotiated size) character buffer exactly as the host "
                + "sent it, plus cursor position and OIA (keyboard-locked / input-inhibited / "
                + "message-wait) state. Without --json, renders as a numbered monospace grid. "
                + "With --json, prints the full ScreenBuffer object (text, rows, cols, cursor, "
                + "oia) for scripting.",
            List.of(
                "--json      Print raw JSON instead of a rendered grid",
                "--session   Session id to read. Default 'default'"
            ),
            List.of("five250 screen", "five250 screen --json | jq .cursor")
        ));

        ENTRIES.put("screenshot", new Entry(
            "Save a PNG picture of the current screen",
            "five250 screenshot [--out <file.png>] [--session <id>]",
            "Renders the live screen buffer as a terminal-style PNG image (character grid, input "
                + "fields underlined, cursor block, a status line with cursor position and keyboard "
                + "state). The image is drawn by the daemon from the buffer itself, not captured "
                + "from a window, so it works with no browser open, in headless suite/script runs, "
                + "and on a machine with no display. Inside a suite use the `screenshot` action "
                + "(value = image name); inside a script call screenshot(name) - both write to the "
                + "project's screenshots/ folder when the run ends.",
            List.of(
                "--out       File to write. A relative path is relative to the current directory. Default: screenshots/<session>.<timestamp>.png in the active project",
                "--json      Print the raw JSON response (path, bytes) instead of 'Saved <path>'",
                "--session   Session id to capture. Default 'default'"
            ),
            List.of("five250 screenshot", "five250 screenshot --out signon.png")
        ));

        ENTRIES.put("fields", new Entry(
            "List every input field on the current screen",
            "five250 fields [--json] [--session <id>]",
            "Enumerates the screen's field table: row, column, length, and whether each field "
                + "is protected (output-only) or numeric-only, plus its current value. Run this "
                + "before writing a `type --label` call so you know what labels/positions "
                + "actually exist on the screen you're targeting.",
            List.of(
                "--json      Print raw JSON instead of a table",
                "--session   Session id to read. Default 'default'"
            ),
            List.of("five250 fields")
        ));

        ENTRIES.put("type", new Entry(
            "Fill an input field, without submitting",
            "five250 type --label <text> --value <value> [--session <id>]\n"
                + "five250 type --row <n> --col <n> --value <value> [--session <id>]",
            "Sets a field's contents directly (does not simulate individual keystrokes). "
                + "--label finds the field immediately to the right of the given label text "
                + "(case-insensitive substring match) - prefer this over --row/--col, since "
                + "label position survives screen layout changes across DDS versions. Does not "
                + "press Enter; follow with `key ENTER` (or another AID key) to submit.",
            List.of(
                "--label     Label text to anchor on, e.g. \"User\" or \"Account Number\"",
                "--row/--col Explicit 1-based screen position, if no reliable label exists",
                "--value     Text to place in the field (required)",
                "--session   Session id to type into. Default 'default'"
            ),
            List.of(
                "five250 type --label \"User\" --value FSALAMAH",
                "five250 type --row 20 --col 7 --value WRKACTJOB"
            )
        ));

        ENTRIES.put("key", new Entry(
            "Send an AID key (submits the screen)",
            "five250 key <NAME> [--session <id>]",
            "Sends an Attention-Identifier key - the 5250 equivalent of a form submit - then "
                + "waits for the keyboard to unlock and the resulting buffer to stabilize before "
                + "returning. Accepts ENTER, PF1-PF24 (also written F1-F24), PA1-PA3, PAGE_UP / "
                + "PAGEUP, PAGE_DOWN / PAGEDOWN, TAB, BACK_TAB, HOME, CLEAR, HELP, SYSREQ, and "
                + "other tn5250j KeyMnemonic names.",
            List.of("<NAME>      AID key name, case-insensitive, e.g. ENTER, F3, PAGEDOWN"),
            List.of("five250 key ENTER", "five250 key F3", "five250 key PAGEDOWN")
        ));

        ENTRIES.put("disconnect", new Entry(
            "Close a session",
            "five250 disconnect [--session <id>]",
            "Closes the socket and drops the session from the daemon's registry. The daemon "
                + "process itself keeps running (other sessions, if any, are unaffected).",
            List.of("--session   Session id to close. Default 'default'"),
            List.of("five250 disconnect")
        ));

        ENTRIES.put("shutdown", new Entry(
            "Stop the background daemon",
            "five250 shutdown",
            "Closes every live session and exits the daemon process entirely (both the TCP "
                + "protocol on 25250 and the GUI/HTTP API on 25251 go down). The next five250 "
                + "command will auto-start a fresh daemon.",
            List.of(),
            List.of("five250 shutdown")
        ));

        ENTRIES.put("run-suite", new Entry(
            "Run a scenario CSV from the command line (e.g. from CI)",
            "five250 run-suite --flow <flow> --file <file> [--session <id>] [--var NAME=VALUE ...] "
                + "[--data-csv <path>] [--disconnect-on-finish] [--timeout <sec>] [--project <name>]",
            "Starts the run in the background daemon exactly like clicking Run All in the GUI, "
                + "then polls and prints each step as it executes, followed by PASS/FAIL and a "
                + "summary. Exits 0 if every scenario passed, 1 if any failed, 2 on a run error, "
                + "3 on timeout — safe to use as a CI gate. --var overrides that scenario file's "
                + "saved ${NAME} values for this run only, without touching the .vars.csv file; "
                + "repeat --var for multiple values. --data-csv runs the whole suite once per row "
                + "of a CSV whose header names are ${NAME} variables (each row's values override "
                + "--var/the saved .vars.csv for that run only) — this is what the auto-generated "
                + "<file>.bat/<file>.sh next to a scenario invoke after every vars save. "
                + "--disconnect-on-finish closes the session when the run ends, pass or fail (or "
                + "even a run error) — same option as the GUI's \"Disconnect when done\" checkbox; "
                + "off by default, so a suite with no disconnect step of its own leaves the session "
                + "exactly as it left it. --project pins which project workspace this run reads its "
                + "scenario/vars from and writes results/extracted/replays to, for this invocation "
                + "only — it does not change the daemon's active project. Omit it and the run uses "
                + "whichever project is currently active (see `five250 project current`), which is "
                + "risky for CI if someone else can switch projects in the GUI; always pass --project "
                + "explicitly in an unattended/CI context.",
            List.of(
                "--flow                  Flow name, e.g. custom-steps",
                "--file                  Scenario file name (no .csv), e.g. self-contained-signon",
                "--session               Session id to run against. Default 'default'",
                "--var                   NAME=VALUE, repeatable. Overrides that name's saved variable value",
                "--data-csv              Path to a CSV of variable sets; runs once per row, aggregates PASS/FAIL",
                "--disconnect-on-finish  Close the session when the run ends, regardless of PASS/FAIL",
                "--timeout               Seconds to wait for the run to finish before giving up. Default 300",
                "--project               Project workspace name to run against, for this call only. Default: whatever's currently active"
            ),
            List.of(
                "five250 run-suite --flow custom-steps --file self-contained-signon",
                "five250 run-suite --flow run-command --file variable-demo --var CMD1=WRKSPLF --var TITLE1=\"Work with All Spooled Files\"",
                "five250 run-suite --flow custom-steps --file recorded --data-csv scenarios/custom-steps/recorded.data.csv",
                "five250 run-suite --flow custom-steps --file nightly-smoke --project acme-prod"
            )
        ));

        ENTRIES.put("run-script", new Entry(
            "Run a project/scripts/<name>.js script from the command line",
            "five250 run-script <name> [--session <id>] [--var NAME=VALUE ...] "
                + "[--disconnect-on-finish] [--timeout <sec>] [--project <name>]",
            "Same async start/poll/print pattern as run-suite: starts the script in the "
                + "background daemon (same call the GUI's Scripts tab Run button makes), then "
                + "polls and prints its current step plus any console.log/console.error output "
                + "as it happens, followed by PASS/FAIL and a summary. Exits 0/1/2/3 same as "
                + "run-suite (pass/fail/run-error/timeout) - safe as a CI gate. --var here is "
                + "NOT the same thing as run-suite's --var: a script has no suite of its own, so "
                + "there's no saved ${NAME}/.vars.csv to override - instead each --var becomes "
                + "that run's args.NAME inside the script (see the `args` global in the Scripts "
                + "tab's editor autocomplete), a way to hand the script values from outside its "
                + "own source (which host to hit, which command to type) without editing the "
                + "script file itself every time. --disconnect-on-finish/--project work exactly "
                + "like run-suite's.",
            List.of(
                "<name>                  Script name (no .js), e.g. save-data-demo",
                "--session               Session id to run against. Default 'default'",
                "--var                   NAME=VALUE, repeatable. Becomes args.NAME inside the script",
                "--disconnect-on-finish  Close the session when the run ends, regardless of PASS/FAIL",
                "--timeout               Seconds to wait for the run to finish before giving up. Default 300",
                "--project               Project workspace name to run against, for this call only. Default: whatever's currently active"
            ),
            List.of(
                "five250 run-script save-data-demo",
                "five250 run-script cli-args-demo --var HOST=pub400.com --var COMMAND=WRKSPLF",
                "five250 run-script nightly-report --disconnect-on-finish --project acme-prod"
            )
        ));

        ENTRIES.put("project", new Entry(
            "Manage project workspaces (list/create/open/current)",
            "five250 project list\n"
                + "five250 project create <name>\n"
                + "five250 project open <name>\n"
                + "five250 project current",
            "A project is its own scenarios/results/extracted/docs/samples tree, so separate "
                + "pieces of work (different customers, environments, or test suites) don't share "
                + "one implicit folder. Exactly one project is active in the daemon at a time — "
                + "switching here (or in the GUI's titlebar) changes it for every API caller, CLI "
                + "included, until changed again. `create` makes a new project under the app's own "
                + "\"projects/\" folder and immediately opens it. A CI job that must not depend on "
                + "whatever's currently open in the GUI should pass `--project <name>` directly to "
                + "`run-suite` instead of relying on this active-project state.",
            List.of(),
            List.of(
                "five250 project list",
                "five250 project create acme-prod",
                "five250 project open acme-prod",
                "five250 project current"
            )
        ));

        ENTRIES.put("help", new Entry(
            "Show this help, or detailed help for one command",
            "five250 help [command]",
            "With no argument, lists every command with a one-line summary. With a command "
                + "name, shows its full usage, every option, and worked examples.",
            List.of(),
            List.of("five250 help", "five250 help type")
        ));
    }

    public static void printGeneral() {
        System.out.println("five250 <command> [options]\n");
        System.out.println("Run with no command (or `serve`) to just bring up the GUI at http://127.0.0.1:" + HttpApi.PORT + ".\n");
        int width = ENTRIES.keySet().stream().mapToInt(String::length).max().orElse(10);
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            System.out.printf("  %-" + width + "s  %s%n", e.getKey(), e.getValue().summary());
        }
        System.out.println("\nRun `five250 help <command>` for full docs, options, and examples.");
    }

    public static void printFor(String cmd) {
        Entry e = ENTRIES.get(cmd);
        if (e == null) {
            System.err.println("No such command: " + cmd + "\n");
            printGeneral();
            return;
        }
        System.out.println(cmd.toUpperCase() + " - " + e.summary());
        System.out.println();
        System.out.println("USAGE");
        for (String line : e.usage().split("\n")) System.out.println("  " + line);
        System.out.println();
        System.out.println("DESCRIPTION");
        System.out.println("  " + wrap(e.description(), 90, "  "));
        if (!e.options().isEmpty()) {
            System.out.println();
            System.out.println("OPTIONS");
            for (String opt : e.options()) System.out.println("  " + opt);
        }
        if (!e.examples().isEmpty()) {
            System.out.println();
            System.out.println("EXAMPLES");
            for (String ex : e.examples()) System.out.println("  " + ex);
        }
    }

    private static String wrap(String text, int width, String indent) {
        StringBuilder out = new StringBuilder();
        int col = indent.length();
        for (String word : text.split(" ")) {
            if (col + word.length() + 1 > width) {
                out.append("\n").append(indent);
                col = indent.length();
            } else if (out.length() > 0) {
                out.append(' ');
                col++;
            }
            out.append(word);
            col += word.length();
        }
        return out.toString();
    }
}
