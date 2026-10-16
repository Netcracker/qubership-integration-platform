// A load-save-load round trip over every document in `src/web/test/workspace/`, run against the
// Jest fake of `vscode` from `tests/fake-vscode/`: the fixtures are read from disk and seeded into
// the fake's filesystem, so the file API writes into an in-memory tree.
//
// A save reformats. `yaml.stringify` drops the `---` marker and the scalar quotes a catalog export
// carries, so these cases compare parsed documents rather than bytes, as
// `src/web/test/suite/file-roundtrip.test.ts:103-110` does at level 2.
import * as fs from "fs";
import * as path from "path";
import type { Uri } from "vscode";
import { resetWorkspace, seedWorkspace, workspaceUri } from "../../../fake-vscode";
import { bindFileApi } from "../../../fake-vscode/fileApi";
import { VSCodeFileApi } from "../../../../src/web/response/file/fileApiImpl";

const FIXTURE_ROOT = path.join(__dirname, "../../../../src/web/test/workspace");

const CHAIN_ID = "1419390b-7423-4fab-853f-67c653e16a86";
const SERVICE_ID = "3b2e9742-139c-4750-bb76-adaf1cc9e3f0";
const CONTEXT_SERVICE_ID = "269327ed-e44d-491e-9f86-4a3010d63108";
const MCP_SERVICE_ID = "0edab1e2-c311-4718-8411-adb16bfa5d54";

type RoundTrip = {
  document: string;
  file: string;
  load: (api: VSCodeFileApi, fileUri: Uri) => Promise<any>;
  save: (api: VSCodeFileApi, fileUri: Uri, document: any) => Promise<void>;
};

// `getMainChain` and `getMainService` take the file uri alone; the context and MCP readers also take
// the id they expect the document to carry.
const ROUND_TRIPS: RoundTrip[] = [
  {
    document: "a chain",
    file: `chains/${CHAIN_ID}/${CHAIN_ID}.chain.cip.yaml`,
    load: (api, fileUri) => api.getMainChain(fileUri),
    save: (api, fileUri, document) => api.writeMainChain(fileUri, document),
  },
  {
    document: "an internal service",
    file: `services/${SERVICE_ID}/${SERVICE_ID}.service.cip.yaml`,
    load: (api, fileUri) => api.getMainService(fileUri),
    save: (api, fileUri, document) => api.writeMainService(fileUri, document),
  },
  {
    document: "a context service",
    file: `services/${CONTEXT_SERVICE_ID}/${CONTEXT_SERVICE_ID}.context-service.cip.yaml`,
    load: (api, fileUri) => api.getContextService(fileUri, CONTEXT_SERVICE_ID),
    save: (api, fileUri, document) => api.writeMainService(fileUri, document),
  },
  {
    document: "an MCP service",
    file: `services/${MCP_SERVICE_ID}/${MCP_SERVICE_ID}.mcp-service.cip.yaml`,
    load: (api, fileUri) => api.getMcpService(fileUri, MCP_SERVICE_ID),
    save: (api, fileUri, document) => api.writeMainService(fileUri, document),
  },
];

// Every file under `src/web/test/workspace/`, keyed by its path relative to that directory.
function fixtureFiles(directory = ""): Record<string, string> {
  const files: Record<string, string> = {};
  for (const entry of fs.readdirSync(path.join(FIXTURE_ROOT, directory), { withFileTypes: true })) {
    const relativePath = directory ? `${directory}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      Object.assign(files, fixtureFiles(relativePath));
    } else {
      files[relativePath] = fs.readFileSync(path.join(FIXTURE_ROOT, relativePath), "utf8");
    }
  }
  return files;
}

describe("the file API round-trips every workspace document", () => {
  let api: VSCodeFileApi;

  beforeEach(() => {
    resetWorkspace();
    seedWorkspace(fixtureFiles());
    api = bindFileApi();
  });

  // A writer that drops a field from the object it was handed would otherwise go unnoticed, so each
  // case keeps a clone of the loaded document and compares the reload against the clone.
  it.each(ROUND_TRIPS)("saves $document with nothing changed", async (trip) => {
    const fileUri = workspaceUri(trip.file);
    const loaded = await trip.load(api, fileUri);
    const before = structuredClone(loaded);
    await trip.save(api, fileUri, loaded);
    const reloaded = await trip.load(api, fileUri);

    expect(reloaded).toEqual(before);
  });

  it.each(ROUND_TRIPS)("saves $document with its name changed and nothing else", async (trip) => {
    const fileUri = workspaceUri(trip.file);
    const before = structuredClone(await trip.load(api, fileUri));
    await trip.save(api, fileUri, { ...structuredClone(before), name: "vsc-round-trip" });
    const reloaded = await trip.load(api, fileUri);

    expect(reloaded).toEqual({ ...before, name: "vsc-round-trip" });
  });
});
