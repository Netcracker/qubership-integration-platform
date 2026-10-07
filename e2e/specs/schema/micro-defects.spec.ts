/**
 * `settleMicroDefects` over hand-built `TestInfo` readings: a case pinned
 * with a `MicroDefect` stays an expected failure only while every error it raised is one the defect
 * `matches`, and `nameMicroSteps` renames a step only in a case pinned for the step names.
 */
import { test, expect } from "@playwright/test";
import type { TestInfo } from "@playwright/test";
import {
  MICRO_STEP_NAMES,
  MICRO_XSLT,
  nameMicroSteps,
  settleMicroDefects,
  type RenamedStep,
} from "../../support/known-defect.js";
import type { RecordedSession, TracedElement } from "../../support/sessions.js";

const UUID = "802f6fed-e3b6-41c3-9518-4ea9d3eaded2";

/** A `toEqual` diff over step names as Playwright prints it, colors included. */
function namesDiff(removed: string[], added: string[]): string {
  return [
    "Error: \x1b[2mexpect(\x1b[22m\x1b[31mreceived\x1b[39m\x1b[2m).\x1b[22mtoEqual\x1b[2m(\x1b[22m\x1b[32mexpected\x1b[39m\x1b[2m) // deep equality\x1b[22m",
    "",
    `\x1b[32m- Expected  - ${removed.length}\x1b[39m`,
    `\x1b[31m+ Received  + ${added.length}\x1b[39m`,
    "",
    "\x1b[2m  Array [\x1b[22m",
    ...removed.map((name) => `\x1b[32m-   "${name}",\x1b[39m`),
    ...added.map((name) => `\x1b[31m+   "${name}",\x1b[39m`),
    '\x1b[2m    "Validate Request",\x1b[22m',
    "\x1b[2m  ]\x1b[22m",
  ].join("\n");
}

/** The diff `runtime-micro` printed for `http-echo.spec.ts` on September 25, 2026. */
const UUID_STEP_DIFF = namesDiff(["HTTP Trigger"], [UUID]);

/** The xslt case's status assertion on the micro engine, with the engine log line it puts in the message. */
const XSLT_500 =
  "Error: the xslt chain failed: org.apache.camel.NoSuchEndpointException: No endpoint could be " +
  "found for: xslt://file:/tmp/chain_tmp/e2e-vhlns6-stylesheet.xsl, please check your classpath " +
  "contains the needed Camel component jar.\n\nexpect(received).toBe(expected) // Object.is equality\n\n" +
  "Expected: 200\nReceived: 500";

const OTHER_500 =
  "Error: the xslt chain failed: no unresolved xslt endpoint in the engine log\n\n" +
  "expect(received).toBe(expected) // Object.is equality\n\nExpected: 200\nReceived: 500";

function reading(descriptions: string[], messages: string[]): TestInfo {
  return {
    expectedStatus: "failed",
    annotations: descriptions.map((description) => ({ type: "fail", description })),
    errors: messages.map((message) => ({ message })),
  } as unknown as TestInfo;
}

function narrowed(descriptions: string[], messages: string[]): string {
  const info = reading(descriptions, messages);
  settleMicroDefects(info, []);
  return info.expectedStatus;
}

test("a pinned micro case whose only error is the defect stays an expected failure", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed([MICRO_STEP_NAMES.title], [UUID_STEP_DIFF])).toBe("failed");
  expect(narrowed([MICRO_STEP_NAMES.title], [namesDiff(["HTTP Trigger", "Chain Call"], [UUID, UUID])])).toBe(
    "failed",
  );
  expect(narrowed([MICRO_XSLT.title], [XSLT_500])).toBe("failed");
});

test("a step-name diff that changes anything but names into UUIDs becomes a real failure", { tag: ["@infra", "@tier1"] }, () => {
  // A UUID line beside a genuine name change: the second step is not the one the chain names.
  expect(narrowed([MICRO_STEP_NAMES.title], [namesDiff(["HTTP Trigger", "Script"], [UUID, "Mapper"])])).toBe(
    "passed",
  );
  // A step the micro engine dropped, and one it added, beside the renamed trigger.
  expect(narrowed([MICRO_STEP_NAMES.title], [namesDiff(["HTTP Trigger", "Script"], [UUID])])).toBe("passed");
  expect(narrowed([MICRO_STEP_NAMES.title], [namesDiff(["HTTP Trigger"], [UUID, UUID])])).toBe("passed");
});

test("a pinned micro case that failed on something else becomes a real failure", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed([MICRO_STEP_NAMES.title], [XSLT_500.replace("500", "400")])).toBe("passed");
  // A UUID diff does not stand in for the xslt defect, and a 500 for another reason does not either.
  expect(narrowed([MICRO_XSLT.title], [UUID_STEP_DIFF])).toBe("passed");
  expect(narrowed([MICRO_XSLT.title], [OTHER_500])).toBe("passed");
  // One matching error does not excuse a second one that matches nothing.
  expect(narrowed([MICRO_STEP_NAMES.title], [UUID_STEP_DIFF, "Error: timeout"])).toBe("passed");
});

test("a case pinned for both defects stays expected while each error matches one of them", { tag: ["@infra", "@tier1"] }, () => {
  const both = [MICRO_STEP_NAMES.title, MICRO_XSLT.title];
  expect(narrowed(both, [UUID_STEP_DIFF, XSLT_500])).toBe("failed");
  expect(narrowed(both, [UUID_STEP_DIFF, OTHER_500])).toBe("passed");
});

test("a test.fail case pinned for anything else is left to narrow itself", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed(["the catalog cannot read a pod status on Kubernetes v1.36"], ["Error: timeout"])).toBe("failed");
});

test("a micro case whose body reached a test.fail() of its own is left to narrow itself", { tag: ["@infra", "@tier1"] }, () => {
  // `test.fail()` with no argument, as a case adds it for a defect both engines share.
  const info = reading([MICRO_STEP_NAMES.title], ["Error: the trigger now receives the id"]);
  info.annotations.push({ type: "fail" });
  settleMicroDefects(info, []);
  expect(info.expectedStatus).toBe("failed");
});

function step(elementName: string, chainElementId: string | null, children: TracedElement[] = []): TracedElement {
  return {
    elementId: `step-${elementName}`,
    chainElementId,
    parentElement: null,
    previousElement: null,
    elementName,
    camelName: "script",
    executionStatus: "COMPLETED_NORMALLY",
    duration: 1,
    children,
  };
}

function session(): RecordedSession {
  return {
    sessionElements: [step(UUID, "trigger-id", [step("Validate Request", null)]), step("Script", "script-id")],
  } as unknown as RecordedSession;
}

const NAMES = new Map([
  ["trigger-id", "HTTP Trigger"],
  ["script-id", "Script"],
]);

test("a case pinned for the step names reads each step by its element's name, and fails on the defect once its body passes", { tag: ["@infra", "@tier1"] }, () => {
  const renamed: RenamedStep[] = [];
  const pinned = reading([MICRO_STEP_NAMES.title], []);
  const named = session();
  nameMicroSteps(pinned, named, NAMES, renamed);
  const names = (named.sessionElements ?? []).flatMap((each) => [each.elementName, ...(each.children ?? []).map((child) => child.elementName)]);
  expect(names).toEqual(["HTTP Trigger", "Validate Request", "Script"]);

  let report = "";
  try {
    settleMicroDefects(pinned, renamed);
  } catch (cause) {
    report = String(cause);
  }
  expect(report).toContain(`"HTTP Trigger" as ${UUID}`);
  // The report is the defect's own error, so the case stays an expected failure.
  expect(MICRO_STEP_NAMES.matches(report)).toBe(true);

  // A body that already failed is narrowed instead of being reported over.
  const failed = reading([MICRO_STEP_NAMES.title], ["Error: timeout"]);
  expect(() => settleMicroDefects(failed, [{ uuid: UUID, name: "HTTP Trigger" }])).not.toThrow();
  expect(failed.expectedStatus).toBe("passed");
});

test("a case without the step-name pin reads the session as the engine wrote it", { tag: ["@infra", "@tier1"] }, () => {
  const renamed: RenamedStep[] = [];
  const unpinned = reading([MICRO_XSLT.title], []);
  const read = session();
  nameMicroSteps(unpinned, read, NAMES, renamed);
  expect((read.sessionElements ?? [])[0].elementName).toBe(UUID);
  expect(renamed).toEqual([]);
  expect(() => settleMicroDefects(unpinned, renamed)).not.toThrow();
});
