/**
 * The defect the `runtime-micro-redeploy` cases meet on their own micro domains, and the selector of
 * the engine log lines that name it.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/** The message both engines raise for a script that does not compile. */
export const COMPILE_FAILURE = "Failed to compile groovy script.";

const SETTLED_DEPLOYED = /settled at DEPLOYED: $/m;
const IGNORED_LOGGED = /the engine logged [1-9]\d* lines ignoring the compile failure/;

/**
 * The micro engine deploys a chain whose script does not compile. Measured on DD k8s:
 * `CompileGroovyScriptsAction` throws from a Camel event notifier, which logs `Error notifying event
 * Route added: … This exception will be ignored.` with the compile failure, so the chain reports
 * DEPLOYED and the pod keeps running. The classic engine reports FAILED. No issue is filed yet.
 */
export const SCRIPT_COMPILE_IGNORED: ConditionalDefect = {
  title: "the micro engine deploys a chain whose script does not compile",
  engine: "micro",
  matches: (message) => SETTLED_DEPLOYED.test(message) && IGNORED_LOGGED.test(message),
};

/** Selects the engine log lines where Camel ignored the compile failure. */
export function ignoredCompileFailure(line: string): boolean {
  return line.includes("This exception will be ignored") && line.includes(COMPILE_FAILURE);
}
