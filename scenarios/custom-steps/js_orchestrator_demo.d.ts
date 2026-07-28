// Auto-generated whenever "js_orchestrator_demo" is saved (steps table or variables) - hand edits here will be overwritten.
// Describes the global JsSuiteRunner binds for this suite's own <file>.js, if it has one.
declare function execute(range: unknown): void;

declare const js_orchestrator_demo: {
  vars: {
    "USER": string;
    "PASSWORD": string;
    "CMD": string;
    "title": string;
  };
  /** Rows a..b (1-based, inclusive) by absolute position in the CSV file. */
  steps(a: number, b: number): unknown;
};
