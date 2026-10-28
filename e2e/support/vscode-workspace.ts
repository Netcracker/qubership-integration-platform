import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { load } from "js-yaml";

const WORKSPACE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../fixtures/vscode/workspace");

/** The mocha workspace's chain: an HTTP trigger connected to a script. */
export const MOCHA_CHAIN_ID = "1419390b-7423-4fab-853f-67c653e16a86";
export const HTTP_TRIGGER = "7f15ea75-516e-4104-81c6-e68550a9b9e5";
export const SCRIPT = "54b4728d-7000-4350-b310-c58b792311c5";

/** The second version of the mocha chain, with an id of its own and the script and the chain renamed. */
export const MOCHA_V2_CHAIN_ID = "e2e20001-0000-4000-8000-000000000001";

/** A chain whose chain call targets the trigger of the callee chain. */
export const CALLER_CHAIN_ID = "e2e20002-0000-4000-8000-000000000001";
export const CHAIN_CALL = "e2e20002-0000-4000-8000-000000000003";
export const CALLEE_CHAIN_ID = "e2e20003-0000-4000-8000-000000000001";
export const CHAIN_TRIGGER = "e2e20003-0000-4000-8000-000000000002";

/** Four elements bound to services, with no container among them. */
export const SERVICES_CHAIN_ID = "e2e20004-0000-4000-8000-000000000001";
export const SERVICES_TRIGGER = "e2e20004-0000-4000-8000-000000000002";
export const SERVICE_CALL = "e2e20004-0000-4000-8000-000000000003";
export const CONTEXT_STORAGE = "e2e20004-0000-4000-8000-000000000004";
export const MCP_TRIGGER = "e2e20004-0000-4000-8000-000000000005";

/** A GraphQL service call followed by a mapper. */
export const GRAPHQL_CHAIN_ID = "e2e20005-0000-4000-8000-000000000001";
export const MAPPER = "e2e20005-0000-4000-8000-000000000004";

/** A chain with no element. */
export const EMPTY_CHAIN_ID = "e2e20006-0000-4000-8000-000000000001";

export const CHECKPOINT_CHAIN_ID = "e2e20008-0000-4000-8000-000000000001";
export const CHECKPOINT = "e2e20008-0000-4000-8000-000000000002";

/** The path of a chain file in `fixtures/vscode/workspace/`, relative to the workspace. */
export const chainFile = (chainId: string) => `chains/${chainId}/${chainId}.chain.cip.yaml`;

/** The file beside the chain file that holds an element's code or mapping, such as `script.cip.groovy`. */
export const resourceFile = (chainId: string, elementId: string, extension: string) =>
  `chains/${chainId}/resources/${elementId}.element.${extension}`;

/** The path of a service file, a context service's, or an MCP service's, relative to the workspace. */
export const serviceFile = (serviceId: string, kind: "service" | "context-service" | "mcp-service" = "service") =>
  `services/${serviceId}/${serviceId}.${kind}.cip.yaml`;

/** The file of a service's specification group. */
export const specificationGroupFile = (serviceId: string, group: string) =>
  `services/${serviceId}/${serviceId}-${group}.specification-group.cip.yaml`;

/** The file of one specification of a service's group. */
export const specificationFile = (serviceId: string, group: string, version: string) =>
  `services/${serviceId}/${serviceId}-${group}-${version}.specification.cip.yaml`;

export interface ChainElement {
  id: string;
  name: string;
  type: string;
  properties?: Record<string, unknown>;
  children?: ChainElement[];
}

export interface ChainFile {
  id: string;
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

/** A service file of any kind: the fields the cases read, each kind holding some of them. */
export interface ServiceFile {
  name: string;
  content: {
    description?: string;
    labels?: string[];
    identifier?: string;
    instructions?: string;
    activeEnvironmentId?: string;
    environments?: { name: string; address: string }[];
  };
}

export const parseService = (text: string) => load(text) as ServiceFile;

/** The top-level element of `chain` with `id`. */
export const element = (chain: ChainFile, id: string) => chain.content.elements.find((each) => each.id === id);
