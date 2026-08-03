// Worked example of the `args` global - values this run was invoked with, e.g.:
//   five250 run-script cli-args-demo --var HOST=pub400.com --var COMMAND=WRKSPLF
// NOT the same thing as a suite's own ${NAME}/.vars.csv substitution - a script has no suite of
// its own, so this is a separate channel for "values this run should use," supplied from OUTSIDE
// the script's own source (a CLI flag, or the GUI's Run dialog) instead of hardcoded here.
// args.MISSING reads as undefined, not an error - use || for a script that should also run fine
// with no --var at all.
const host = args.HOST || 'pub400.com';
const command = args.COMMAND || 'WRKACTJOB';

connect(host, 23);
try {
  const signon = importSuite('custom-steps', 'signon-common');
  signon.vars.USER = 'your-pub400-user';
  signon.vars.PASSWORD = 'your-pub400-password';
  execute(signon.all());

  console.log('args.HOST=' + args.HOST + ' args.COMMAND=' + args.COMMAND);
  console.log('resolved host=' + host + ' command=' + command);
} finally {
  disconnect();
}
