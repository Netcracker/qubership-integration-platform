/**
 * The defects the broker cases meet on the micro copy.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";
import { NO_LINE_FOR_CHAIN } from "../../support/logs.js";

/**
 * An `sftp-trigger-2` on a micro domain never polls its directory. Measured on a micro domain: each
 * consumer logs in once at start and creates its directory, then the SFTP server sees no other login
 * from the pod, a dropped file records no session, and the engine logs no line for the chain. The
 * classic engine polls every 3 s. No issue is filed yet.
 */
export const SFTP_TRIGGER_IDLE: ConditionalDefect = {
  title: "an sftp-trigger-2 on the micro engine never polls its directory",
  engine: "micro",
  matches: (message) => NO_LINE_FOR_CHAIN.test(message),
};
