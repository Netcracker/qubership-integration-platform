import { parse } from "yaml";
import {
  resetWorkspace,
  seedWorkspace,
  workspaceTree,
  workspaceUri,
} from "../fake-vscode";
import { bindFileApi } from "../fake-vscode/fileApi";
import { getContextService } from "../../src/web/response/serviceApiRead";
import { updateContextService } from "../../src/web/response/serviceApiModify";

const ID = "ctx-1";
const FILE = `services/${ID}/${ID}.context-service.cip.yaml`;

describe("getContextService <-> updateContextService – the labels on disk", () => {
  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({
      [FILE]: `id: ${ID}\nname: Orders Context\ncontent:\n  labels:\n    - billing\n`,
    });
    bindFileApi();
  });

  // ContextServiceParametersTab spreads the service it read into the update and sends every label in the field.
  test("a label added to the labels read back is written beside them", async () => {
    const asShownInTheUi = await getContextService(workspaceUri(FILE), ID);
    expect(asShownInTheUi.labels).toEqual([
      { name: "billing", technical: false },
    ]);

    await updateContextService(workspaceUri(FILE), ID, {
      ...asShownInTheUi,
      labels: [
        ...(asShownInTheUi.labels ?? []),
        { name: "orders", technical: false },
      ],
    });

    const written = parse(workspaceTree()[FILE]) as {
      content: { labels: string[] };
    };
    expect(written.content.labels).toEqual(["billing", "orders"]);
  });
});
