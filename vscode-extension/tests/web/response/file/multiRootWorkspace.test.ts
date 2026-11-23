import { resetWorkspace, seedWorkspace, workspace, workspaceUri } from "../../../fake-vscode";
import { bindFileApi } from "../../../fake-vscode/fileApi";

describe("a workspace with two folders", () => {
  beforeEach(() => {
    resetWorkspace();
    workspace.workspaceFolders = [
      { uri: workspaceUri("first") as any, name: "first", index: 0 },
      { uri: workspaceUri("second") as any, name: "second", index: 1 },
    ];
  });

  test("keeps the second folder when a chain stored in its root is deleted", async () => {
    seedWorkspace({ "second/root.chain.cip.yaml": "id: root\nname: Root\ncontent: {}\n" });

    const directories = await bindFileApi().getDirectoriesToRemove(
      workspaceUri("second/root.chain.cip.yaml"),
    );

    expect(directories).toEqual([]);
  });
});
