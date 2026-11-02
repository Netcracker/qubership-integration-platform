import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { load } from "js-yaml";

const WORKSPACE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../fixtures/vscode/workspace");

/** The path of a chain file in `fixtures/vscode/workspace/`, relative to the workspace. */
export const chainFile = (chainId: string) => `chains/${chainId}/${chainId}.chain.cip.yaml`;

/** The path of a service file in `fixtures/vscode/workspace/`, relative to the workspace. */
export const serviceFile = (serviceId: string) => `services/${serviceId}/${serviceId}.service.cip.yaml`;

export interface ChainElement {
  id: string;
  name: string;
  type: string;
  properties?: Record<string, unknown>;
  children?: ChainElement[];
}

export interface ChainFile {
  name: string;
  metaInfo?: { group?: string };
  content: {
    description?: string;
    labels?: string[];
    elements: ChainElement[];
    dependencies: { from: string; to: string }[];
    maskedFields?: { id: string; name: string }[];
  };
}

/** The path on disk of a workspace file, for a case that uploads it. */
export const fixturePath = (relativePath: string) => path.join(WORKSPACE, relativePath);

/** The text of a workspace file as the fixture holds it. */
export const fixture = (relativePath: string) => readFileSync(fixturePath(relativePath), "utf8");

export const parseChain = (text: string) => load(text) as ChainFile;

/** The top-level element of `chain` with `id`. */
export const element = (chain: ChainFile, id: string) => chain.content.elements.find((each) => each.id === id);
