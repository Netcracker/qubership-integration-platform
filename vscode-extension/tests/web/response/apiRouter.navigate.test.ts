import { resetWorkspace, seedWorkspace, workspaceUri } from "../../fake-vscode";
import { bindFileApi } from "../../fake-vscode/fileApi";
import { getApiResponse } from "../../../src/web/response/apiRouter";

const CHAIN = "chains/c-1/c-1.chain.cip.yaml";
const SERVICE = "services/s-1/s-1.service.cip.yaml";
const UNPARSABLE = "name: a: b";

function navigate(path: string): Promise<unknown> {
  return getApiResponse(
    { requestId: "1", type: "navigate" },
    workspaceUri(path),
  );
}

describe("apiRouter navigate handler", () => {
  beforeEach(() => {
    resetWorkspace();
    bindFileApi();
  });

  test("navigates to the graph of a chain file that parses", async () => {
    seedWorkspace({ [CHAIN]: "id: c-1\nname: Chain\n" });

    await expect(navigate(CHAIN)).resolves.toBe("/chains/c-1/graph");
  });

  test("navigates to /services for a file of an unknown type", async () => {
    seedWorkspace({ "notes.txt": "text" });

    await expect(navigate("notes.txt")).resolves.toBe("/services");
  });

  test.each([CHAIN, SERVICE])(
    "rejects with the parser's message for %s that does not parse",
    async (path) => {
      seedWorkspace({ [path]: UNPARSABLE });

      await expect(navigate(path)).rejects.toThrow(
        "Failed to parse content: not valid JSON or YAML",
      );
    },
  );
});
