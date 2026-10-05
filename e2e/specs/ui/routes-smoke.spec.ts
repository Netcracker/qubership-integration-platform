/**
 * Every route the layer covers opens with the page guard armed.
 *
 * The floor of the browser layer, and the only spec here allowed to assert that something merely
 * rendered: what it asserts is that no screen throws, logs an error, or gets a 4xx or 5xx from its
 * own `/api/` call, and the guard fails the case naming the route. One case per route family, with
 * the ids a route needs created through the API.
 *
 * Every route `ui/src/App.tsx` mounts is opened here, with these exceptions. Other specs open three
 * with the ids they need: an element's form at `/chains/:id/graph/:elementId`
 * (`chain-graph.spec.ts`), a session (`chain-tabs.spec.ts`), and a test case run with its errors
 * (`testing-section.spec.ts`). The admin test run screens show the same components as the last.
 * Left out entirely: the redirects `/` and `/admintools/engine-list`, the documentation pages under
 * `/doc`, `/not-implemented`, and the not-found page, which `testing-section.spec.ts` reaches.
 */
import type { Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

/** A route to open, and the path it settles on once its own redirects have run. */
interface Route {
  open: string;
  settles?: string;
}

async function visit(page: Page, routes: readonly Route[]): Promise<void> {
  for (const route of routes) {
    await test.step(route.open, async () => {
      await page.goto(route.open);
      // Every request the screen sends on load has answered, so the guard has seen its status.
      await page.waitForLoadState("networkidle");
      await expect(page).toHaveURL((url) => url.pathname === (route.settles ?? route.open));
      // The innermost landmark: an admin or developer tool renders its own inside the app's.
      await expect(page.getByRole("main").last()).not.toBeEmpty();
    });
  }
}

test("the chains list opens", { tag: ["@ui", "@tier1"] }, async ({ page }) => {
  await visit(page, [{ open: "/chains" }]);
});

test("every tab of a chain opens", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-smoke-tabs"), folder.id);
  const base = `/chains/${chain.id}`;
  await visit(
    page,
    ["graph", "snapshots", "deployments", "sessions", "properties", "logging-settings", "masking"].map((tab) => ({
      open: `${base}/${tab}`,
    })),
  );
});

test("a service, its specification and its environments open", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "ui-smoke-service"), "EXTERNAL");
  try {
    const groupName = tokenized(run, "ui-smoke-group");
    const started = await catalog.importSpecificationGroup(
      service.id,
      groupName,
      readSpecificationFixture("widgets.openapi.yaml"),
      "http",
    );
    const imported = await catalog.awaitSpecificationImport(started);
    await catalog.createEnvironment(service.id, {
      name: tokenized(run, "ui-smoke-env"),
      address: "http://ui-smoke.example",
    });
    const groupId = started.specificationGroupId;
    const [specification] = imported.specifications;
    const [operation] = imported.operations;
    const system = `/services/systems/${service.id}`;
    const specifications = `${system}/specificationGroups/${groupId}/specifications`;
    await visit(page, [
      { open: "/services" },
      { open: `${system}/parameters` },
      { open: `${system}/specificationGroups` },
      { open: specifications },
      { open: `${specifications}/${specification.id}/operations` },
      { open: `${specifications}/${specification.id}/operations/${operation.id}` },
      { open: `${system}/environments` },
    ]);
  } finally {
    await catalog.deleteSystem(service.id).catch(leftBehind(`service ${service.name}`));
  }
});

test("a context service and an MCP service open", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  const context = await catalog.createContextSystem(tokenized(run, "ui-smoke-context"));
  const mcp = await catalog.createMcpSystem({ name: tokenized(run, "ui-smoke-mcp"), identifier: tokenized(run, "ui-smoke-mcp") });
  try {
    await visit(page, [{ open: `/services/context/${context.id}/parameters` }, { open: `/services/mcp/${mcp.id}/parameters` }]);
  } finally {
    await catalog.deleteContextSystem(context.id).catch(leftBehind(`context service ${context.name}`));
    await catalog.deleteMcpSystem(mcp.id).catch(leftBehind(`MCP service ${mcp.name}`));
  }
});

test("every admin tool opens", { tag: ["@ui", "@tier1"] }, async ({ page }) => {
  await visit(
    page,
    [
      "variables/common",
      "variables/secured",
      "audit",
      "sessions",
      "import-instructions",
      "access-control",
      "exchanges",
      "domains",
      "detailed-design/templates",
    ].map((tool) => ({ open: `/admintools/${tool}` })),
  );
});

test("every developer tool opens", { tag: ["@ui", "@tier1"] }, async ({ page }) => {
  await visit(page, [
    { open: "/devtools", settles: "/devtools/maas/kafka" },
    { open: "/devtools/maas/rabbitmq" },
    { open: "/devtools/diagnostic/validations" },
  ]);
});

test("the testing section opens under admin tools and inside a chain", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, testingService, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "ui-smoke-testing"), folder.id);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  const sender = await catalog.createElement(chain.id, "http-sender");
  const testCase = await testingService.createTestCase({
    name: tokenized(run, "ui-smoke-test-case"),
    enabled: false,
    trigger: { chainId: chain.id, elementId: trigger.id },
    method: "POST",
  });
  try {
    // On a sender of the case's own chain, so no other worker's call is answered by it.
    const onSender = { chainId: chain.id, elementId: sender.id };
    await testingService.withMock({ name: tokenized(run, "ui-smoke-mock"), reference: onSender, response: { status: 200, body: "{}" } }, async (mock) => {
      const inChain = `/chains/${chain.id}/testing`;
      // A redirect to `/not-found` is what `TestingGuard` does when the bundle hides the section, and
      // the path check below is what catches it.
      await visit(page, [
        { open: "/admintools/testing", settles: "/admintools/testing/test-cases" },
        { open: "/admintools/testing/endpoint-mocks" },
        { open: "/admintools/testing/test-runs" },
        { open: inChain, settles: `${inChain}/test-cases` },
        { open: `${inChain}/endpoint-mocks` },
        { open: `${inChain}/test-case-runs` },
        ...["general", "request", "response-validation"].map((tab) => ({ open: `${inChain}/test-cases/${testCase.id}/${tab}` })),
        ...["general", "response", "request-matchers"].map((tab) => ({ open: `${inChain}/endpoint-mocks/${mock.id}/${tab}` })),
      ]);
    });
  } finally {
    await testingService.deleteTestCase(testCase.id).catch(leftBehind(`test case ${testCase.name}`));
  }
});
