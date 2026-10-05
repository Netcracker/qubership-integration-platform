/**
 * Services and their specifications, created through the UI.
 *
 * Both cases have the creation flow as their subject, so the data is clicked into existence rather
 * than created through the API. Each still asserts what only the
 * browser decides. The service dialog prefills a name and, for an internal service, sends a second
 * request that creates an environment. The import dialog names the group after the chosen file,
 * and the API tab redraws the group from its own refresh.
 *
 * Two scenarios an earlier draft listed are left out. Creating and activating an environment is
 * covered by `specs/api/services.spec.ts`, and nothing in it is browser-only. The context and MCP
 * parameter forms rendering is the button-exists assertion under another name.
 */
import path from "node:path";
import { test, expect } from "../../support/page-guard.js";
import { ServicesPage } from "../../pages/ServicesPage.js";
import { TableView } from "../../pages/TableView.js";
import { SPECIFICATION_FIXTURE_DIR } from "../../fixtures/templating.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

test("an internal service created through the dialog gets the environment the UI adds", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  const services = new ServicesPage(page);
  const name = tokenized(run, "ui-service-create");
  const description = `created by ${run}`;

  await services.goto("internal");
  await services.createService.click();
  const nameField = services.createDialog.getByRole("textbox", { name: "Name" });
  await expect(nameField).toBeFocused();
  await expect(nameField).toHaveValue("New internal service");
  await nameField.fill(name);
  await services.createDialog.getByRole("textbox", { name: "Description" }).fill(description);
  const created = page.waitForResponse((response) => response.request().method() === "POST" && response.url().endsWith("/systems-catalog/systems"));
  await services.createDialog.getByRole("button", { name: "Create" }).click();
  const { id } = (await (await created).json()) as { id: string };

  try {
    await expect(page).toHaveURL(`/services/systems/${id}/parameters`);
    expect(await catalog.getSystem(id)).toMatchObject({ name, type: "INTERNAL", description });
    // `ServicesList.handleCreate` follows an internal or implemented service with an environment
    // of its own name at `/`; the catalog's create adds none.
    const environments = await catalog.listEnvironments(id);
    expect(environments.map((each) => ({ name: each.name, address: each.address }))).toEqual([{ name, address: "/" }]);
  } finally {
    await catalog.deleteSystem(id).catch(leftBehind(`service ${name}`));
  }
});

test("a specification imported through the file dialog shows its operations", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, run }) => {
  const service = await catalog.createSystem(tokenized(run, "ui-spec-import"), "EXTERNAL");
  const services = new ServicesPage(page);
  const table = new TableView(page);
  try {
    await services.gotoSpecificationGroups(service.id);
    await services.addSpecificationGroup.click();
    await services.chooseSpecificationFile(path.join(SPECIFICATION_FIXTURE_DIR, "widgets.openapi.yaml"));
    // The group name comes from the file name when the field was left alone.
    await expect(services.importGroupDialog.getByRole("textbox", { name: "Name" })).toHaveValue("widgets.openapi");
    await services.importGroupDialog.getByRole("button", { name: "Import File" }).click();
    // The dialog polls the import and closes once it is done, and the groups table reloads with no
    // page reload.
    await expect(services.importGroupDialog).toBeHidden();
    await expect(services.row("widgets.openapi")).toBeVisible();

    await services.open("widgets.openapi");
    await expect(page).toHaveURL(new RegExp(`/services/systems/${service.id}/specificationGroups/[^/]+/specifications$`));
    await services.open("1.0.0");
    await expect(page).toHaveURL(/\/specifications\/[^/]+\/operations$/);

    // Name, method badge and path of each row, as the operations table draws them.
    const operations = async () => {
      const found: string[] = [];
      for (const row of await table.rows.all()) {
        const [operation, method, url] = (await row.getByRole("cell").allInnerTexts()).map((each) => each.trim());
        found.push(`${method} ${url} ${operation}`);
      }
      return found.sort();
    };
    await expect
      .poll(operations)
      .toEqual(["GET /widgets listWidgets", "GET /widgets/{id} getWidget", "POST /widgets createWidget"]);
  } finally {
    await catalog.deleteSystem(service.id).catch(leftBehind(`service ${service.name}`));
  }
});
