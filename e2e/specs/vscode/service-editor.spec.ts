/**
 * The service editor in VS Code: the Parameters, API Specifications, and Environments tabs of a
 * service file, and what each edit writes to the service's files.
 *
 * The tables are read again from the extension after every edit, so a row that changes shows the
 * file the extension wrote. A case reads a file as text where the file holds more than a row shows.
 *
 * Three types stay uncovered, since no screen of the extension sends them: `createService` comes
 * from the services list, which is not among the extension's routes, and the editor moves between
 * its tables with its own router rather than through `navigateToSpecifications` or
 * `navigateToOperations`.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/` run alone:
 * - `getSpecificationModel` answering `[]`: every case that opens a group found no specification;
 * - `getOperationInfo` answering `{}`: the details dialog showed no specification;
 * - `updateService` skipping its write: the file read timed out on the old name;
 * - `createEnvironment` skipping its write: the edit after it failed with `EnvironmentId not
 *   found`, which the guard caught too;
 * - `updateEnvironment` skipping its write: the file kept the added address;
 * - `deleteEnvironment` skipping its write: the file read timed out on two environments;
 * - `getSpecApiFiles` answering `[]`: the dialog offered no contract to pick;
 * - `readSpecificationFileContent` answering `""`: the import failed on an unknown format, and the
 *   guard caught the extension host's error;
 * - `importSpecificationGroup` and `importSpecification` each throwing, and
 *   `getImportSpecificationResult` never reporting done: the import dialog stayed open;
 * - the import writing no specification file (`SpecificationImportService`): the group showed no
 *   `1.0.0` row, since the tables read the files the extension wrote;
 * - `updateApiSpecificationGroup` and `updateSpecificationModel` each skipping its write: the row
 *   never showed the label;
 * - `deprecateModel` skipping its write: the status stayed `In use`, in both cases that deprecate;
 * - `deleteSpecificationModel` and `deleteSpecificationGroup` each skipping its delete: the row
 *   stayed.
 */
import type { Locator } from "@playwright/test";
import { load } from "js-yaml";
import { test, expect } from "../../support/vscode-guard.js";
import { TableView } from "../../pages/TableView.js";
import type { VsCodePage } from "../../pages/VsCodePage.js";
import { addTag } from "../../support/pick-option.js";
import {
  fixturePath,
  parseService,
  serviceFile,
  specificationFile,
  specificationGroupFile,
  type ServiceFile,
} from "../../support/vscode-workspace.js";

/** The mocha workspace's internal service: one group, `orders`, with one specification, `1.0.0`. */
const INTERNAL_ID = "3b2e9742-139c-4750-bb76-adaf1cc9e3f0";
const INTERNAL = serviceFile(INTERNAL_ID);
const GROUP_FILE = specificationGroupFile(INTERNAL_ID, "orders");
const SPEC_FILE = specificationFile(INTERNAL_ID, "orders", "1.0.0");

/** An external service with one environment and no specification. Only an external service offers Add Environment. */
const EXTERNAL = serviceFile("e2e2000a-0000-4000-8000-000000000001");

/** The second version of the API contract's document: 2.0.0, with `listWidgets` only. */
const WIDGETS_V2 = fixturePath("apis/widgets-v2.openapi.yaml");

/** Column headers, whose names carry their resize handles. */
const LABELS = "Labels Resize column";
const STATUS = "Status Resize column";

interface SpecificationFile {
  content: { labels?: string[]; deprecated?: boolean };
}

/** Opens `relativePath` as text and returns its parsed YAML once `until` holds for it. */
async function readYaml<T>(vscode: VsCodePage, relativePath: string, until: (parsed: T) => boolean): Promise<T> {
  await vscode.openFile(relativePath);
  return load(await vscode.readText((text) => until(load(text) as T))) as T;
}

test.describe("service editor", () => {
  test("the API tab opens a group down to an operation's details", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(INTERNAL);
    const { webview } = vscode;
    const table = new TableView(webview);
    await expect(webview.getByRole("textbox", { name: "* Name" })).toHaveValue("e2e-service-roundtrip-internal");

    await webview.getByRole("tab", { name: "API Specifications" }).click();
    await table.row("orders").getByText("orders", { exact: true }).click();
    await table.row("1.0.0").getByText("1.0.0", { exact: true }).click();
    const operation = table.row("createOrder");
    await expect(operation).toHaveText(/createOrder\s*POST\s*\/orders/);

    await operation.getByText("createOrder", { exact: true }).click();
    const details = webview.getByRole("dialog", { name: "Operation info" });
    await expect(details.getByRole("tabpanel", { name: "Specification" })).toContainText('"operationId": "createOrder"');
    await details.getByRole("tab", { name: "Request schema" }).click();
    await expect(details.getByRole("tabpanel", { name: "Request schema" })).toContainText('"quantity"');
    expect(messages.answered()).toEqual(expect.arrayContaining(["getService", "getApiSpecifications", "getSpecificationModel", "getOperationInfo"]));
  });

  test("saved parameters are written to the service file", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(INTERNAL);
    const { webview } = vscode;

    await webview.getByRole("textbox", { name: "* Name" }).fill("vsc-renamed-service");
    await webview.getByRole("textbox", { name: "Description" }).fill("set in VS Code");
    const labels = webview.getByRole("combobox", { name: "Labels" });
    await addTag(webview, labels, "e2e-label");
    // The tag list stays open over the Save button.
    await labels.press("Escape");
    const save = webview.getByRole("button", { name: "Save" });
    await save.click();
    await expect(save).toBeDisabled();

    const service = parseService(await vscode.readAsText((text) => parseService(text).name === "vsc-renamed-service"));
    expect({ name: service.name, description: service.content.description, labels: service.content.labels }).toEqual({
      name: "vsc-renamed-service",
      description: "set in VS Code",
      labels: ["e2e-label"],
    });
    expect(messages.answered()).toContain("updateService");
  });

  test("an environment is added and edited, and another is deleted", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(EXTERNAL);
    const { webview } = vscode;
    const table = new TableView(webview);
    await webview.getByRole("tab", { name: "Environments" }).click();

    await webview.getByRole("button", { name: "Add Environment" }).click();
    const added = webview.getByRole("dialog", { name: "Add Environment" });
    await added.getByRole("textbox", { name: "Name" }).fill("e2e-added");
    await added.getByRole("textbox", { name: "* Address" }).fill("http://e2e-added.invalid");
    await added.getByRole("button", { name: "Save" }).click();
    await expect(added).toBeHidden();
    await expect(table.row("e2e-added")).toContainText("http://e2e-added.invalid");

    await table.row("e2e-added").getByRole("button", { name: "e2e-added" }).click();
    const edited = webview.getByRole("dialog", { name: "Edit Environment" });
    await edited.getByRole("textbox", { name: "* Address" }).fill("http://e2e-edited.invalid");
    await edited.getByRole("button", { name: "Save" }).click();
    await expect(edited).toBeHidden();
    await expect(table.row("e2e-added")).toContainText("http://e2e-edited.invalid");

    await table.row("primary").getByTestId("environments-delete").click();
    await webview.getByRole("dialog", { name: "Are you sure you want to delete this Environment?" }).getByRole("button", { name: "OK" }).click();
    await expect(table.row("primary")).toHaveCount(0);

    const environments = (each: ServiceFile) => (each.content.environments ?? []).map(({ name, address }) => ({ name, address }));
    const service = parseService(await vscode.readAsText((text) => environments(parseService(text)).length === 1));
    expect(environments(service)).toEqual([{ name: "e2e-added", address: "http://e2e-edited.invalid" }]);
    // The deleted environment was the active one.
    expect(service.content.activeEnvironmentId).toBe("");
    expect(messages.answered()).toEqual(expect.arrayContaining(["createEnvironment", "updateEnvironment", "deleteEnvironment"]));
  });

  test("a group imported from an API contract, then a second version", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(EXTERNAL);
    const { webview } = vscode;
    const table = new TableView(webview);
    await webview.getByRole("tab", { name: "API Specifications" }).click();

    await webview.getByTestId("api-specs-add-group").click();
    const groupImport = webview.getByRole("dialog", { name: "Import Specification Group" });
    // The contract list filters by the start of a name, and Enter picks the one option left.
    const contract = groupImport.getByRole("combobox");
    await contract.fill("vsc-fixture-widgets");
    await contract.press("Enter");
    await expect(groupImport.getByRole("textbox", { name: "Enter group name" })).toHaveValue("vsc-fixture-widgets-api");
    await groupImport.getByRole("button", { name: "Import", exact: true }).click();
    await expect(groupImport).toBeHidden();

    await table.row("vsc-fixture-widgets-api").getByText("vsc-fixture-widgets-api", { exact: true }).click();
    await table.row("1.0.0").getByText("1.0.0", { exact: true }).click();
    await expect(table.rows).toHaveCount(3);
    for (const operation of ["listWidgets", "createWidget", "getWidget"]) await expect(table.row(operation)).toBeVisible();

    await webview.getByRole("button", { name: "To Specifications" }).click();
    await webview.getByTestId("api-specs-import-specification").click();
    const fileImport = webview.getByRole("dialog", { name: "Import Specification" });
    await fileImport.locator("input[type=file]").setInputFiles(WIDGETS_V2);
    await fileImport.getByRole("button", { name: "Import File" }).click();
    await expect(fileImport).toBeHidden();

    await table.row("2.0.0").getByText("2.0.0", { exact: true }).click();
    await expect(table.rows).toHaveCount(1);
    await expect(table.row("listWidgets")).toBeVisible();
    expect(messages.answered()).toEqual(
      expect.arrayContaining(["getSpecApiFiles", "readSpecificationFileContent", "importSpecificationGroup", "importSpecification", "getImportSpecificationResult"]),
    );
  });

  test("group and specification labels and a deprecation are written", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(INTERNAL);
    const { webview } = vscode;
    const table = new TableView(webview);
    await webview.getByRole("tab", { name: "API Specifications" }).click();

    await addLabel(vscode, await table.cell(table.row("orders"), LABELS), "e2e-group");
    await table.row("orders").getByText("orders", { exact: true }).click();
    const spec = table.row("1.0.0");
    // The group table has Labels one column further left, so the cell is looked up only once the
    // specification table shows.
    await expect(spec).toBeVisible();
    await addLabel(vscode, await table.cell(spec, LABELS), "e2e-spec");
    await expect(await table.cell(spec, STATUS)).toHaveText("In use");
    await runRowAction(vscode, spec, "Deprecate", "Deprecate this specification?");
    await expect(await table.cell(spec, STATUS)).toHaveText("Deprecated");

    const group = await readYaml<SpecificationFile>(vscode, GROUP_FILE, (each) => each.content.labels !== undefined);
    expect(group.content.labels).toEqual(["e2e-group"]);
    const model = await readYaml<SpecificationFile>(vscode, SPEC_FILE, (each) => each.content.deprecated === true);
    expect(model.content).toMatchObject({ labels: ["e2e-spec"], deprecated: true });
    expect(messages.answered()).toEqual(expect.arrayContaining(["updateApiSpecificationGroup", "updateSpecificationModel", "deprecateModel"]));
  });

  test("a deprecated specification and then its group are deleted", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(INTERNAL);
    const { webview } = vscode;
    const table = new TableView(webview);
    await webview.getByRole("tab", { name: "API Specifications" }).click();
    await table.row("orders").getByText("orders", { exact: true }).click();

    const spec = table.row("1.0.0");
    await runRowAction(vscode, spec, "Deprecate", "Deprecate this specification?");
    await expect(await table.cell(spec, STATUS)).toHaveText("Deprecated");
    await runRowAction(vscode, spec, "Delete", "Delete this specification?");
    await expect(vscode.notifications).toContainText('Specification "1.0.0" has been deleted successfully!');
    await expect(spec).toHaveCount(0);

    await webview.getByRole("button", { name: "To Specification Groups" }).click();
    const group = table.row("orders");
    await runRowAction(vscode, group, "Delete", "Are you sure you want to permanently delete this specification group?");
    await expect(vscode.notifications).toContainText('Specification group "orders" has been deleted successfully!');
    await expect(group).toHaveCount(0);
    expect(messages.answered()).toEqual(expect.arrayContaining(["deleteSpecificationModel", "deleteSpecificationGroup"]));
  });
});

/** Adds `label` in a row's inline labels editor, which Enter saves, and waits for the row to show it. */
async function addLabel(vscode: VsCodePage, cell: Locator, label: string): Promise<void> {
  await cell.getByRole("button").click();
  const select = cell.getByRole("combobox");
  await expect(select).toBeFocused();
  await addTag(vscode.webview, select, label);
  await expect(cell.getByRole("button", { name: label, exact: true })).toBeVisible();
}

/** Runs a row's action from its menu and confirms it in the dialog `confirmTitle` names. */
async function runRowAction(vscode: VsCodePage, row: Locator, action: string, confirmTitle: string): Promise<void> {
  await row.getByTestId("services-row-actions").click();
  await vscode.webview.getByRole("menuitem", { name: action }).click();
  const confirm = vscode.webview.getByRole("dialog", { name: confirmTitle });
  await confirm.getByRole("button", { name: action }).click();
  await expect(confirm).toBeHidden();
}
