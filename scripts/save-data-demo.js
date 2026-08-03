// Worked example of saveJson()/saveCsv() - writing a script's own data out to disk, separate
// from the automatic per-run results/extracted dumps every run already gets. Both write to
// extracted/scripts/<this script's name>.<name>.json (or .csv), overwriting on every call.
connect('pub400.com', 23);
try {
  const demo = importSuite('custom-steps', 'js_orchestrator_demo');
  demo.vars.USER = 'your-pub400-user';
  demo.vars.PASSWORD = 'your-pub400-password';
  demo.vars.CMD = 'WRKACTJOB';
  execute(demo.steps('login-start', 'after-login'));

  // saveJson(name, data) - any JSON-shaped value: object, array, string, number, boolean, null.
  // Lands at extracted/scripts/save-data-demo.summary.json
  saveJson('summary', {
    host: 'pub400.com',
    command: demo.vars.CMD,
    landedOnTitle: demo.vars.title,
    checkedAt: demo.vars.title.indexOf('Welcome') !== -1 ? 'signon-page' : 'unknown-page',
  });

  // saveCsv(name, rows) - an array of flat objects; the CSV's columns come from the first row's
  // own keys. Lands at extracted/scripts/save-data-demo.observations.csv
  saveCsv('observations', [
    { field: 'command', value: demo.vars.CMD },
    { field: 'title', value: demo.vars.title },
  ]);

  console.log('wrote extracted/scripts/save-data-demo.summary.json and .observations.csv');
} finally {
  disconnect();
}
