/**
 * The slice of Playwright's JSON reporter output the registries and the case catalog read.
 *
 * It sits apart from `elements.ts` because three readers need it — the coverage reconciliation, the
 * operation reconciliation and the case catalog in `support/report.ts` — and a reporter's wire
 * format is not the element registry's business. `elements.ts` re-exports the types, so nothing
 * that imports them from there has to move.
 *
 * Types only, and that is load-bearing rather than tidy: `import type` is erased, so a consumer
 * loaded under `node --experimental-strip-types` never resolves this file. A runtime export here
 * would not reach `elements.ts`, where a `.js` specifier pointing at a `.ts` file resolves to
 * nothing at all.
 */

/** The invocation, as Playwright records it. Absent from a hand-built report. */
export interface JsonConfig {
  /** The process arguments, `node` and the CLI entry point included. */
  argv?: string[];
  /**
   * The resolved project list — after `playwright.config.ts` has already decided which projects
   * exist, `E2E_BROKERS=0` included. `runFilters` reads this to catch a narrowing `argv` carries no
   * trace of: the env var never reaches the command line, but it removes the brokers-family
   * projects from this list before Playwright ever runs.
   */
  projects?: { name?: string }[];
  /** `playwright.config.ts` records the run's target here, and reconciliation reads it. */
  metadata?: { target?: string };
}

export interface JsonReport {
  config?: JsonConfig;
  suites?: JsonSuite[];
  stats?: {
    expected?: number;
    unexpected?: number;
    skipped?: number;
    flaky?: number;
    /** Wall time of the whole run, in milliseconds. The case catalog opens with it. */
    duration?: number;
    startTime?: string;
  };
}

export interface JsonSuite {
  suites?: JsonSuite[];
  specs?: JsonSpec[];
}

export interface JsonSpec {
  title?: string;
  file?: string;
  /** Playwright reports a test's tags here, **without** the leading `@`. */
  tags?: string[];
  tests?: JsonTest[];
}

export interface JsonTest {
  /** Playwright's outcome: `expected` means the test ended the way it was declared to end. */
  status?: string;
  /**
   * What the test was declared to end as. `failed` is what `test.fail()` writes.
   *
   * `status` alone cannot answer whether a test proved anything: a `test.fail()` case that failed
   * is reported `expected`, and its body stopped at the first assertion that went red. So a proof
   * reads both fields, and `test.fail()` proves nothing either way.
   */
  expectedStatus?: string;
  annotations?: { type?: string; description?: string }[];
  /** Which project ran the test. The case catalog prints it; reconciliation ignores it. */
  projectName?: string;
  /** One entry per attempt. With `retries: 0` there is exactly one, and it carries the trace. */
  results?: JsonResult[];
}

/** One attempt at a test, narrowed to what the case catalog reads. */
export interface JsonResult {
  status?: string;
  duration?: number;
  attachments?: { name?: string; path?: string; contentType?: string }[];
}
