import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import * as yaml from "yaml";
import { ExtensionContext, Uri } from "vscode";
import type {
  Chain,
  DataType,
  Element as ElementSchema,
} from "@netcracker/qip-schemas";
import type { Element, PatchElementRequest } from "@netcracker/qip-ui";
import {
  cloneElements,
  updateElement,
} from "../../../src/web/response/chainApiModify";
import { getElement } from "../../../src/web/response/chainApiRead";
import { fileApi, setFileApi } from "../../../src/web/response/file";
import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";

jest.mock("vscode", () => {
  const disk =
    jest.requireActual<typeof import("node:fs/promises")>("node:fs/promises");
  const paths = jest.requireActual<typeof import("node:path")>("node:path");
  const uri = (filename: string): Uri =>
    ({
      path: filename,
      fsPath: filename,
      scheme: "file",
      with: (changes: { path?: string }) => uri(changes.path ?? filename),
      toString: () => filename,
    }) as Uri;

  return {
    Uri: {
      file: uri,
      joinPath: (base: Uri, ...segments: string[]) =>
        uri(paths.join(base.path, ...segments)),
    },
    FileType: { File: 1, Directory: 2 },
    workspace: {
      fs: {
        stat: async (file: Uri) => {
          const info = await disk.stat(file.fsPath);
          return { type: info.isDirectory() ? 2 : 1 };
        },
        readFile: (file: Uri) => disk.readFile(file.fsPath),
        writeFile: (file: Uri, content: Uint8Array) =>
          disk.writeFile(file.fsPath, content),
        createDirectory: (file: Uri) =>
          disk.mkdir(file.fsPath, { recursive: true }),
        delete: (file: Uri) => disk.rm(file.fsPath),
      },
    },
    window: {
      showInformationMessage: jest.fn(),
      showErrorMessage: jest.fn(),
    },
  };
});

jest.mock("../../../src/web/response/apiRouter", () => ({
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
  SERVICE_ROUTES: [],
}));

type ResourceType = "script" | "mapper-2";
type ResourceLocation = "element" | "before" | "after";
type Properties = Record<string, any>;

const chainId = "resource-chain";
const originalId = "original-element";
let directory: string;
let fileUri: Uri;

beforeEach(async () => {
  directory = await fs.mkdtemp(
    path.join(os.tmpdir(), "qip-resource-lifecycle-"),
  );
  fileUri = Uri.file(path.join(directory, `${chainId}.chain.qip.yaml`));
  setFileApi(
    new VSCodeFileApi({
      extensionUri: Uri.file(path.resolve(__dirname, "../../..")),
    } as ExtensionContext),
  );
});

afterEach(async () => {
  await fs.rm(directory, { recursive: true, force: true });
});

function resourceProperty(type: ResourceType): string {
  return type === "script" ? "script" : "mappingDescription";
}

function resourceContent(type: ResourceType, value: string): string {
  return type === "script"
    ? value
    : JSON.stringify({ mappingDescription: value });
}

function resourceBlock(
  properties: Properties,
  location: ResourceLocation,
): Properties {
  if (location === "before") {
    return properties.before;
  }
  if (location === "after") {
    return properties.after[0];
  }
  return properties;
}

async function seedResource(
  type: ResourceType,
  location: ResourceLocation,
  filename: string,
  value: string,
): Promise<void> {
  const resource = { type, propertiesFilename: filename };
  const properties: Properties =
    location === "element"
      ? {
          propertiesFilename: filename,
          propertiesToExportInSeparateFile: resourceProperty(type),
          exportFileExtension: type === "script" ? "groovy" : "json",
        }
      : location === "before"
        ? { before: resource }
        : { after: [{ ...resource, id: "response-handler", code: "200" }] };
  const element = {
    id: originalId,
    name: "Original element",
    type: (location === "element"
      ? type
      : "service-call") as unknown as DataType,
    properties,
  } as ElementSchema;
  const chain = {
    $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml",
    id: chainId,
    name: "Resource chain",
    content: { elements: [element], dependencies: [] },
  } as Chain;

  await fs.mkdir(path.join(directory, "resources"));
  await fs.writeFile(fileUri.fsPath, yaml.stringify(chain));
  await fs.writeFile(
    path.join(directory, "resources", filename),
    resourceContent(type, value),
  );
}

async function save(element: Element, properties: Properties): Promise<void> {
  await updateElement(fileUri, chainId, element.id, {
    name: element.name,
    description: element.description ?? "",
    parentElementId: element.parentElementId,
    properties,
  } as PatchElementRequest);
}

const resources: { type: ResourceType; location: ResourceLocation }[] = [
  { type: "script", location: "element" },
  { type: "mapper-2", location: "element" },
  { type: "script", location: "before" },
  { type: "mapper-2", location: "before" },
  { type: "script", location: "after" },
  { type: "mapper-2", location: "after" },
];

describe("cloned element resources", () => {
  it.each(resources)(
    "keeps $location $type content independent after editing either copy",
    async ({ type, location }) => {
      const filename =
        type === "script" ? "custom-script.groovy" : "custom-mapping.json";
      const originalValue =
        type === "script" ? 'println "original"' : '{"mappings":["original"]}';
      const cloneValue =
        type === "script" ? 'println "clone"' : '{"mappings":["clone"]}';
      const editedOriginalValue =
        type === "script"
          ? 'println "edited original"'
          : '{"mappings":["edited original"]}';
      await seedResource(type, location, filename, originalValue);

      const [clone] = await cloneElements(fileUri, chainId, [originalId]);
      expect(clone.id).not.toBe(originalId);
      expect(
        resourceBlock(clone.properties!, location)[resourceProperty(type)],
      ).toBe(originalValue);

      resourceBlock(clone.properties!, location)[resourceProperty(type)] =
        cloneValue;
      await save(clone, clone.properties!);

      const original = await getElement(fileUri, chainId, originalId);
      const savedClone = await getElement(fileUri, chainId, clone.id);
      const originalResource = resourceBlock(original.properties!, location);
      const cloneResource = resourceBlock(savedClone.properties!, location);
      expect(originalResource[resourceProperty(type)]).toBe(originalValue);
      expect(originalResource.propertiesFilename).toBe(filename);
      expect(cloneResource[resourceProperty(type)]).toBe(cloneValue);
      expect(cloneResource.propertiesFilename).not.toBe(filename);
      expect(await fileApi.readFile(fileUri, filename)).toBe(
        resourceContent(type, originalValue),
      );

      originalResource[resourceProperty(type)] = editedOriginalValue;
      await save(original, original.properties!);

      const rereadClone = await getElement(fileUri, chainId, clone.id);
      expect(
        resourceBlock(rereadClone.properties!, location)[
          resourceProperty(type)
        ],
      ).toBe(cloneValue);
      const rereadOriginal = await getElement(fileUri, chainId, originalId);
      expect(
        resourceBlock(rereadOriginal.properties!, location)[
          resourceProperty(type)
        ],
      ).toBe(editedOriginalValue);
    },
  );
});

describe("service-call before resources", () => {
  it.each<ResourceType>(["script", "mapper-2"])(
    "changes the filename when switching from %s",
    async (oldType) => {
      const newType: ResourceType =
        oldType === "script" ? "mapper-2" : "script";
      const oldFilename =
        oldType === "script" ? "custom-before.groovy" : "custom-before.json";
      const oldValue =
        oldType === "script" ? 'println "original"' : '{"mappings":[]}';
      const newValue =
        newType === "script" ? 'println "changed"' : '{"mappings":["changed"]}';
      await seedResource(oldType, "before", oldFilename, oldValue);

      const element = await getElement(fileUri, chainId, originalId);
      const properties = element.properties as Properties;
      properties.before = {
        ...properties.before,
        type: newType,
        [resourceProperty(newType)]: newValue,
      };
      delete properties.before[resourceProperty(oldType)];
      await save(element, properties);

      const chain = await fileApi.getMainChain(fileUri);
      const storedBefore = (
        (chain.content.elements as ElementSchema[])[0].properties as Properties
      ).before;
      const suffix =
        newType === "script" ? ".script.cip.groovy" : ".mapper.cip.json";
      expect(storedBefore.propertiesFilename).toBe(
        `${originalId}.before${suffix}`,
      );
      expect(storedBefore[resourceProperty(newType)]).toBeUndefined();
      const storedContent = await fileApi.readFile(
        fileUri,
        storedBefore.propertiesFilename,
      );
      if (newType === "script") {
        expect(storedContent).toBe(newValue);
      } else {
        expect(JSON.parse(storedContent)).toEqual({
          mappingDescription: newValue,
        });
      }
      expect(await fs.readdir(path.join(directory, "resources"))).toEqual([
        storedBefore.propertiesFilename,
      ]);
      const reread = await getElement(fileUri, chainId, originalId);
      expect(
        (reread.properties as Properties).before[resourceProperty(newType)],
      ).toBe(newValue);
    },
  );

  it.each<ResourceType>(["script", "mapper-2"])(
    "preserves the custom filename when editing a %s without changing its type",
    async (type) => {
      const filename =
        type === "script" ? "custom-before.groovy" : "custom-before.json";
      const value =
        type === "script" ? 'println "edited"' : '{"mappings":["edited"]}';
      await seedResource(type, "before", filename, "original");
      const element = await getElement(fileUri, chainId, originalId);
      const properties = element.properties as Properties;
      properties.before[resourceProperty(type)] = value;

      await save(element, properties);

      const reread = await getElement(fileUri, chainId, originalId);
      expect((reread.properties as Properties).before).toMatchObject({
        propertiesFilename: filename,
        [resourceProperty(type)]: value,
      });
      expect(await fs.readdir(path.join(directory, "resources"))).toEqual([
        filename,
      ]);
    },
  );
});
