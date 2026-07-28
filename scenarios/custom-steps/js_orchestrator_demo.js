// suiteX.vars.NAME = value  ->  feeds ${NAME} substitution for the NEXT execute() call.
js_orchestrator_demo.vars.CMD = 'WRKACTJOB';

// Row 1 (the "connect" step) already ran automatically before this script started - the same
// self-contained-signon convenience plain CSV suites get. Rows 2-8 sign on and navigate to
// WRKACTJOB, extracting its title along the way.
execute(js_orchestrator_demo.steps(2, 8));

// An "extract" step inside that range wrote straight into .vars - readable immediately.
console.log('landed on: ' + js_orchestrator_demo.vars.title);
if (js_orchestrator_demo.vars.title.indexOf('Active Jobs') === -1) {
  throw new Error('did not land on WRKACTJOB, got: ' + js_orchestrator_demo.vars.title);
}

// A real JS loop calling execute() repeatedly - the direct JS-native analog of a CSV
// "loop count" step, without needing any CSV control-flow syntax at all.
for (var i = 0; i < 3; i++) {
  execute(js_orchestrator_demo.steps(9, 9)); // row 9: key F5 (refresh)
}

execute(js_orchestrator_demo.steps(10, 10)); // row 10: key F3 (exit)
