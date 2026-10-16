// The two `FileType.File` masks in `qipExplorer.ts`. A chain or service file reached through a
// symbolic link stats as `File | SymbolicLink`, and the strict comparisons these replaced left such
// a file out of the tree altogether.
//
// These cases run at level 1 because `vscode.workspace.fs` has no member that creates a link, as
// `src/web/test/suite/file-system.test.ts` measured: only a seed in the Jest fake can plant one. The
// level-2 explorer cases walk the same two lines, but over plain files, where a mask and a strict
// comparison answer alike.
import { FileType, TreeItemCollapsibleState } from "vscode";
import { resetWorkspace, seedWorkspace } from "../fake-vscode";
import { bindFileApi } from "../fake-vscode/fileApi";
import { QipExplorerItem, QipExplorerProvider } from "../../src/web/qipExplorer";

const SYMLINKED_FILE = FileType.File | FileType.SymbolicLink;

const PLAIN_CHAIN_ID = "b4a1f2c6-3d57-4e8a-9b02-7c1e5f3a6d84";
const LINKED_CHAIN_ID = "e7d2c0b5-9a14-4f63-8c5e-2b7a4d1f9068";
const PLAIN_SERVICE_ID = "a1c3e5f7-2b40-4d68-9e1a-3f5c7b9d2048";
const LINKED_SERVICE_ID = "f0b9d7c5-4e21-4a36-8d70-1c2e4a6b8042";

const chainYaml = (id: string, name: string) =>
  `id: ${id}\nname: ${name}\ncontent:\n  elements: []\n`;

const serviceYaml = (id: string, name: string) =>
  `id: ${id}\nname: ${name}\ncontent:\n  protocol: http\n  integrationSystemType: EXTERNAL\n`;

// `getChildren` is entered at the category level, which is how the tree view asks for the chains or
// the services of a category it has already rendered.
const category = (label: string): QipExplorerItem => ({
  id: `${label.toLowerCase()}-category`,
  label,
  contextValue: `qip-${label.toLowerCase()}-category`,
  collapsibleState: TreeItemCollapsibleState.Collapsed,
  type: "category",
});

describe("the explorer tree over a symbolic link", () => {
  let explorer: QipExplorerProvider;

  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({
      [`chains/${PLAIN_CHAIN_ID}/${PLAIN_CHAIN_ID}.chain.cip.yaml`]: chainYaml(
        PLAIN_CHAIN_ID,
        "plain chain",
      ),
      [`chains/${LINKED_CHAIN_ID}/${LINKED_CHAIN_ID}.chain.cip.yaml`]: {
        content: chainYaml(LINKED_CHAIN_ID, "linked chain"),
        type: SYMLINKED_FILE,
      },
      [`services/${PLAIN_SERVICE_ID}/${PLAIN_SERVICE_ID}.service.cip.yaml`]: serviceYaml(
        PLAIN_SERVICE_ID,
        "plain service",
      ),
      [`services/${LINKED_SERVICE_ID}/${LINKED_SERVICE_ID}.service.cip.yaml`]: {
        content: serviceYaml(LINKED_SERVICE_ID, "linked service"),
        type: SYMLINKED_FILE,
      },
    });
    // `ContentParser` reads every file through the facade.
    bindFileApi();
    explorer = new QipExplorerProvider();
  });

  it("lists a chain file reached through a link beside a plain one", async () => {
    const chains = await explorer.getChildren(category("Chains"));

    expect(chains.map((item) => item.label)).toEqual([
      `linked chain-${LINKED_CHAIN_ID}`,
      `plain chain-${PLAIN_CHAIN_ID}`,
    ]);
  });

  it("lists a service file reached through a link beside a plain one", async () => {
    const services = await explorer.getChildren(category("Services"));

    expect(services.map((item) => item.label)).toEqual([
      `linked service-http-${LINKED_SERVICE_ID}`,
      `plain service-http-${PLAIN_SERVICE_ID}`,
    ]);
  });
});
