import * as fs from "node:fs";
import * as path from "node:path";
import { parse } from "yaml";
import {
  resetWorkspace,
  seedWorkspace,
  workspace,
  workspaceTree,
  workspaceUri,
} from "../../fake-vscode";
import { bindFileApi } from "../../fake-vscode/fileApi";
import { duplicateChain } from "../../../src/web/response/chainApiModify";

const SOURCE = "chains/orders/orders.chain.cip.yaml";
const SCRIPT_FILE = "chains/orders/resources/script.element.script.cip.groovy";
const SOURCE_YAML = `$schema: http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/chain
id: orders
name: Orders
metaInfo:
  group: billing
content:
  defaultSwimlaneId: lane
  maskedFields:
    - id: masked
      name: password
  deployments:
    - domain: default
  deployAction: DEPLOY
  elements:
    - id: lane
      name: Default
      type: swimlane
    - id: trigger
      name: Chain Trigger
      type: chain-trigger-2
      swimlaneId: lane
      properties:
        elementId: trigger
    - id: script
      name: Script
      type: script
      swimlaneId: lane
      properties:
        exportFileExtension: groovy
        propertiesFilename: script.element.script.cip.groovy
        propertiesToExportInSeparateFile: script
    - id: group
      name: Container
      type: container
      children:
        - id: reuse
          name: Reuse
          type: reuse
        - id: reference
          name: Reuse Reference
          type: reuse-reference
          properties:
            reuseElementId: reuse
  dependencies:
    - from: trigger
      to: script
`;

describe("duplicateChain", () => {
  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({
      [SOURCE]: SOURCE_YAML,
      [SCRIPT_FILE]: "log.info('hi')",
      "chains/copy/copy.chain.cip.yaml": "id: copy\nname: Orders (1)\nmetaInfo:\n  group: billing\ncontent: {}\n",
      "chains/elsewhere/elsewhere.chain.cip.yaml": "id: elsewhere\nname: Orders (2)\ncontent: {}\n",
    });
    const library = JSON.parse(
      fs.readFileSync(path.join(__dirname, "../../../media/library.json"), "utf-8"),
    );
    jest.spyOn(bindFileApi(), "getLibrary").mockResolvedValue(library);
  });

  test("writes a copy with new ids, references remapped, and its own property files", async () => {
    const result = await duplicateChain(workspaceUri(SOURCE));

    expect(result.name).toBe("Orders (2)");
    const tree = workspaceTree();
    const relativePath = result.uri.path.replace("/workspace/", "");
    const copy = parse(tree[relativePath]);
    expect(relativePath).toBe(`chains/${copy.id}/${copy.id}.chain.cip.yaml`);
    expect(copy.id).not.toBe("orders");
    expect(copy.metaInfo.group).toBe("billing");
    expect(copy.content.deployments).toBeUndefined();
    expect(copy.content.deployAction).toBeUndefined();

    const [lane, trigger, script, group] = copy.content.elements;
    const [reuse, reference] = group.children;
    const oldIds = ["lane", "trigger", "script", "group", "reuse", "reference"];
    for (const element of [lane, trigger, script, group, reuse, reference]) {
      expect(oldIds).not.toContain(element.id);
    }
    expect(copy.content.defaultSwimlaneId).toBe(lane.id);
    expect(trigger.swimlaneId).toBe(lane.id);
    expect(trigger.properties.elementId).toBe(trigger.id);
    expect(reference.properties.reuseElementId).toBe(reuse.id);
    expect(copy.content.dependencies).toEqual([{ from: trigger.id, to: script.id }]);
    expect(copy.content.maskedFields[0].id).not.toBe("masked");
    expect(copy.content.maskedFields[0].name).toBe("password");

    expect(script.properties.propertiesFilename).toBe(`${script.id}.element.script.cip.groovy`);
    expect(tree[`chains/${copy.id}/resources/${script.properties.propertiesFilename}`]).toBe("log.info('hi')");
    expect(tree[SOURCE]).toBe(SOURCE_YAML);
    expect(tree[SCRIPT_FILE]).toBe("log.info('hi')");
  });

  test("puts the copy of a chain stored in the workspace root under the root", async () => {
    seedWorkspace({ "root.chain.cip.yaml": "id: root\nname: Root\ncontent: {}\n" });

    const result = await duplicateChain(workspaceUri("root.chain.cip.yaml"));

    const copy = parse(workspaceTree()[result.uri.path.replace("/workspace/", "")]);
    expect(result.uri.path).toBe(`/workspace/${copy.id}/${copy.id}.chain.cip.yaml`);
  });

  test("puts the copy of a chain stored in the root of a second workspace folder under that folder", async () => {
    workspace.workspaceFolders = [
      { uri: workspaceUri("first") as any, name: "first", index: 0 },
      { uri: workspaceUri("second") as any, name: "second", index: 1 },
    ];
    seedWorkspace({ "second/root.chain.cip.yaml": "id: root\nname: Root\ncontent: {}\n" });

    const result = await duplicateChain(workspaceUri("second/root.chain.cip.yaml"));

    const copy = parse(workspaceTree()[result.uri.path.replace("/workspace/", "")]);
    expect(result.uri.path).toBe(`/workspace/second/${copy.id}/${copy.id}.chain.cip.yaml`);
  });

  test("writes nothing when another chain in the workspace cannot be parsed", async () => {
    seedWorkspace({ "chains/broken/broken.chain.cip.yaml": "id: [broken" });
    const before = workspaceTree();

    await expect(duplicateChain(workspaceUri(SOURCE))).rejects.toThrow();

    expect(workspaceTree()).toEqual(before);
  });

  test("writes nothing when a service call has a deprecated mapper handler", async () => {
    seedWorkspace({
      "chains/legacy/legacy.chain.cip.yaml": `id: legacy
name: Legacy
content:
  elements:
    - id: script
      name: Script
      type: script
      properties:
        exportFileExtension: groovy
        propertiesFilename: script.groovy
        propertiesToExportInSeparateFile: script
    - id: call
      name: Service Call
      type: service-call
      properties:
        before:
          type: mapper
          propertiesFilename: before.json
`,
      "chains/legacy/resources/script.groovy": "log.info('hi')",
      "chains/legacy/resources/before.json": '{"mapping": {}, "source": {}, "target": {}}',
    });
    const before = workspaceTree();

    await expect(
      duplicateChain(workspaceUri("chains/legacy/legacy.chain.cip.yaml")),
    ).rejects.toThrow("Deprecated Mapper element is not supported");

    expect(workspaceTree()).toEqual(before);
  });
});
