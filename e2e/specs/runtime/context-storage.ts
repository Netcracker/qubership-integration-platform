/**
 * The defect the context-storage cases meet on a micro domain.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

/**
 * The micro engine stores a context value outside a transaction. `ContextStorageService.storeValue`
 * calls `persist` with no `@Transactional` around it, so every SET fails, and the step records
 * `Transaction is not active, consider adding @Transactional to your method to automatically
 * activate one.` Measured on a micro domain; the classic engine stores the value. No issue is filed
 * yet.
 */
export const CONTEXT_STORE_UNTRANSACTED: ConditionalDefect = {
  title: "the micro engine stores a context value outside a transaction",
  engine: "micro",
  matches: (message) => message.includes("Transaction is not active"),
};
