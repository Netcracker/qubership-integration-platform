/**
 * The testing section inside a chain: test cases created, edited, and run from the UI.
 *
 * The section opens only when the testing service reports `production: false` and the bundle was
 * built with `VITE_PRODUCTION_MODE` other than `true` (`useTestingServiceAvailability`). The
 * `ui-server` project builds with it `false`, and `routes-smoke.spec.ts` asserts that the section
 * opens. The first case here covers the service's half of the rule: the stack cannot be switched to
 * a live installation per case, so the case rewrites the mode answer. The bundle's half is left to
 * Jest (`ui/tests/hooks/useTestingServiceAvailability.test.tsx`): asserting it here would take a
 * second bundle build, about a minute per run, to cover a value Vite bakes into the bundle.
 *
 * The creation case is the one whose subject is the creation flow. It asserts what the dialog
 * decides and the service does not: the trigger it preselects and the method it derives from that
 * trigger. The rule and run cases create their test case through the API.
 *
 * Endpoint mocks and their matchers are left out: `specs/api/testing-service-mocks.spec.ts` covers
 * the testing service's API, and saving a mock's matchers is an API fact. What stays is the matcher
 * editor's own validation, which runs in the browser and never reaches the service.
 *
 * Every test case is created on the seed `http-echo` chain and deleted with its test runs when the
 * case ends; the run-token sweep does not cover test cases.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { TableView } from "../../pages/TableView.js";
import { TestingPage } from "../../pages/TestingPage.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

/** The seed chain every case uses, and the design-time id of its one HTTP trigger. */
function httpEcho() {
  const chain = seedChain(readCorpusState(), "http-echo");
  return { chain, trigger: { chainId: chain.id, elementId: chain.elements["HTTP Trigger"] } };
}

test("the testing section is hidden while the service reports a live installation", { tag: ["@ui", "@tier1"] }, async ({ page }) => {
  const { chain } = httpEcho();
  await page.route("**/testing-service/mode", (route) => route.fulfill({ json: { production: true } }));
  // The section also stays hidden while the mode is loading, so each absence is read only after the
  // page has the answer.
  const modeAnswered = () => page.waitForResponse("**/testing-service/mode");

  let mode = modeAnswered();
  await new ChainGraphPage(page).goto(chain.id);
  await mode;
  await expect(page.getByRole("tab", { name: "Graph" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Testing" })).toHaveCount(0);

  // A bookmark into the section lands on the not-found page from either mount point.
  await new TestingPage(page).goto(chain.id, "test-cases");
  await expect(page).toHaveURL((url) => url.pathname === "/not-found");
  await page.goto("/admintools/testing/test-cases");
  await expect(page).toHaveURL((url) => url.pathname === "/not-found");

  mode = modeAnswered();
  await page.goto("/admintools/domains");
  await mode;
  await expect(page.getByRole("menuitem", { name: "Domains" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Testing" })).toHaveCount(0);
});

test("a test case created on a chain's testing tab is bound to the chain's HTTP trigger and its method", { tag: ["@ui", "@tier1"] }, async ({ page, testingService, run }) => {
  const { chain, trigger } = httpEcho();
  const name = tokenized(run, "ui-test-case-create");
  const testing = new TestingPage(page);

  await new ChainGraphPage(page).goto(chain.id);
  await page.getByRole("tab", { name: "Testing" }).click();
  await expect(page).toHaveURL((url) => url.pathname === `/chains/${chain.id}/testing/test-cases`);
  await testing.createTestCase.click();
  const nameField = testing.createDialog.getByTestId("test-case-name");
  await expect(nameField).toBeFocused();
  // The dialog preselects the chain's first HTTP trigger; `http-echo` has one.
  await expect(testing.createDialog.getByTitle("HTTP Trigger", { exact: true })).toBeVisible();
  await nameField.fill(name);
  const created = page.waitForResponse((response) => response.request().method() === "POST" && response.url().endsWith("/test-cases/create"));
  await testing.createDialog.getByRole("button", { name: "Save" }).click();
  const { id } = (await (await created).json()) as { id: string };

  try {
    await expect(page).toHaveURL(`/chains/${chain.id}/testing/test-cases/${id}/general`);
    await expect(page.getByRole("textbox", { name: "Name" })).toHaveValue(name);
    const stored = await testingService.getTestCase(id);
    expect(stored.triggerReference).toEqual(trigger);
    // The trigger restricts itself to POST, and the dialog stores the method it accepts, not GET.
    expect(stored.requestSettings?.method).toBe("POST");
    expect(stored.enabled).toBe(false);
  } finally {
    await testingService.deleteTestCase(id).catch(leftBehind(`test case ${name}`));
  }
});

test("a new response rule is marked invalid and blocks the save until its parameter is set", { tag: ["@ui", "@tier1"] }, async ({ page, testingService, run }) => {
  const { chain, trigger } = httpEcho();
  const testCase = await testingService.createTestCase({
    name: tokenized(run, "ui-test-case-rules"),
    enabled: true,
    trigger,
    method: "POST",
  });
  const testing = new TestingPage(page);
  const invalid = page.getByTestId("matcher-parameters-invalid");
  try {
    await testing.gotoTestCase(chain.id, testCase.id, "response-validation");
    await page.getByRole("button", { name: "Add matcher" }).click();

    // A new rule is an Equals over the body with no value, which the service would refuse.
    await expect(invalid).toBeVisible();
    await expect(testing.save).toBeDisabled();
    await invalid.hover();
    await expect(page.getByRole("tooltip", { name: "Missing parameter: value", exact: true })).toBeVisible();

    await page.getByRole("button", { name: "Name is required" }).click();
    await page.getByRole("textbox", { name: "Matcher name" }).fill("echoes the run");
    await page.keyboard.press("Enter");
    await invalid.getByRole("button", { name: "Not set" }).click();
    await page.getByRole("textbox", { name: "value" }).fill(run);
    await page.keyboard.press("Enter");
    await expect(invalid).toHaveCount(0);
    await expect(testing.save).toBeEnabled();

    // Another condition drops the parameters of the old one, so the rule is invalid again. The
    // condition editor opens with its list already open.
    await page.getByRole("button", { name: "Equals" }).click();
    await page.getByTitle("Matches pattern", { exact: true }).click();
    await expect(invalid).toBeVisible();
    await expect(testing.save).toBeDisabled();
    await invalid.hover();
    await expect(page.getByRole("tooltip", { name: "Missing parameter: pattern", exact: true })).toBeVisible();

    await invalid.getByRole("button", { name: "Not set" }).click();
    await page.getByRole("textbox", { name: "pattern" }).fill(`^.*${run}.*$`);
    await page.keyboard.press("Enter");
    await expect(invalid).toHaveCount(0);
    await testing.save.click();

    await expect
      .poll(async () => (await testingService.getTestCase(testCase.id)).responseValidationRules)
      .toMatchObject([
        { name: "echoes the run", type: "match", entityType: "body", parameters: [{ name: "pattern", value: `^.*${run}.*$` }] },
      ]);
  } finally {
    await testingService.deleteTestCase(testCase.id).catch(leftBehind(`test case ${testCase.name}`));
  }
});

test("a test case run from the chain's list shows its result, its session, and its failed rule", { tag: ["@ui", "@tier1"] }, async ({ page, testingService, sessions, run }) => {
  const { chain, trigger } = httpEcho();
  const name = tokenized(run, "ui-test-case-run");
  // `http-echo` answers 200 with the body it was sent, so the first rule holds and the second fails.
  const testCase = await testingService.createTestCase({
    name,
    enabled: true,
    trigger,
    method: "POST",
    body: JSON.stringify({ ping: run }),
    rules: [
      { name: "echoes the run", type: "contain", entityType: "body", value: run },
      { name: "answers created", type: "equal", entityType: "status", value: "201" },
    ],
  });
  const testing = new TestingPage(page);
  const table = new TableView(page);
  let runId: string | undefined;
  try {
    await testing.goto(chain.id, "test-cases");
    await table.row(name).getByRole("checkbox").check();
    const started = page.waitForResponse((response) => response.url().endsWith("/tests-runs/create"));
    await testing.runTestCases.click();
    runId = (await (await started).json()) as string;
    await expect(page.getByText("Test run started")).toBeVisible();

    // The list reads its runs and their sessions once, and a session lookup that misses answers 404,
    // so the screen opens only after the run has finished and its session is recorded.
    let sessionId = "";
    await expect
      .poll(async () => {
        const [caseRun] = await testingService.caseRunsOf(runId!);
        sessionId = caseRun?.sessionId ?? "";
        return caseRun?.status;
      })
      .toBe("finished");
    const session = await sessions.byExternalId(sessionId);

    await testing.section("Test Case Runs").click();
    const caseRun = table.row(name);
    // The session cell joins the case run with the sessions service, which the testing service does not.
    await expect(caseRun.getByRole("link", { name: sessionId })).toHaveAttribute(
      "href",
      `/chains/${chain.id}/sessions/${session.id}`,
    );
    await caseRun.getByRole("link", { name: "1", exact: true }).click();

    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/testing/test-case-runs/[0-9a-f-]{36}$`));
    const errors = page.getByRole("row").filter({ has: page.getByRole("link") });
    await expect(errors).toHaveCount(1);
    await expect(errors.getByRole("link", { name: "answers created" })).toBeVisible();
    await expect(errors).toContainText("200");
  } finally {
    if (runId) await testingService.deleteTestsRun(runId).catch(leftBehind(`test run ${runId}`));
    await testingService.deleteTestCase(testCase.id).catch(leftBehind(`test case ${name}`));
  }
});
