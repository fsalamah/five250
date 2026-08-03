// A script opens and closes its own session - connect() is a no-op if this one's already live,
// disconnect() in the finally means the session never leaks, pass or fail.
connect('pub400.com', 23);
try {
  // A script has no suite of its own any more - pull in whichever suite(s) you need explicitly.
  // This demo happens to use the suite that shares its old name, but that's just naming, not a
  // binding: importSuite() works the same for any suite in any flow.
  const demo = importSuite('custom-steps', 'js_orchestrator_demo');

  // demo.vars.NAME = value  ->  feeds ${NAME} substitution for the NEXT execute() call, scoped to
  // this imported suite's own vars map - a different importSuite() call gets its own map entirely.
  demo.vars.CMD = 'WRKACTJOB';

  // Row 1 is a CSV-level "connect" step - only ever honored as a pre-run pseudo-case for a plain
  // suite run, never inside execute() - our own connect() call above already covers it, so it's
  // deliberately skipped here. "login-start"..."after-login" are this suite's own id cells (see
  // its id column) naming the sign-on-and-navigate-to-WRKACTJOB range - steps() takes either a
  // string id or a raw row number, and can mix the two.
  execute(demo.steps('login-start', 'after-login'));

  // An "extract" step inside that range wrote straight into .vars - readable immediately.
  console.log('landed on: ' + demo.vars.title);
  if (demo.vars.title.indexOf('Active Jobs') === -1) {
    throw new Error('did not land on WRKACTJOB, got: ' + demo.vars.title);
  }

  // A real JS loop calling execute() repeatedly - the direct JS-native analog of a CSV
  // "loop count" step, without needing any CSV control-flow syntax at all.
  for (var i = 0; i < 3; i++) {
    execute(demo.steps(9, 9)); // row 9: key F5 (refresh)
  }

  execute(demo.steps(10, 10)); // row 10: key F3 (exit)
} finally {
  disconnect();
}
