import type { Catalog } from "./catalog.js";
import { tokenized } from "./run-token.js";

/** The response header the header modification adds, and the handle on which version answered. */
export const MARKER_HEADER = "e2e-marker";

export interface DeployableChain {
  id: string;
  name: string;
  contextPath: string;
  triggerId: string;
  headerElementId: string;
}

export interface DeployableOptions {
  name: string;
  parentId: string;
  contextPath: string;
  marker: string;
  /** Puts the trigger on the public gateway, for a case about external routes. */
  externalRoute?: boolean;
}

/** The properties a live HTTP trigger needs, merged over the ones creation supplied. */
export function triggerProperties(contextPath: string, externalRoute = false): Record<string, unknown> {
  return { contextPath, httpMethodRestrict: "POST", externalRoute };
}

export function markerProperties(marker: string): Record<string, unknown> {
  return { headerModificationToAdd: { [MARKER_HEADER]: marker }, headerModificationToRemove: [] };
}

/** Creates the chain and wires it. It is not snapshotted or deployed — the spec decides that. */
export async function createDeployableChain(
  catalog: Catalog,
  options: DeployableOptions,
): Promise<DeployableChain> {
  const chain = await catalog.createChain(options.name, options.parentId);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(
    chain.id,
    trigger.id,
    triggerProperties(options.contextPath, options.externalRoute),
  );
  const header = await catalog.createElement(chain.id, "header-modification");
  await catalog.patchElementProperties(chain.id, header.id, markerProperties(options.marker));
  await catalog.connectElements(chain.id, trigger.id, header.id);
  return {
    id: chain.id,
    name: chain.name,
    contextPath: options.contextPath,
    triggerId: trigger.id,
    headerElementId: header.id,
  };
}

export interface TokenizedChainOptions {
  /** Which spec the chain belongs to, so two specs asking for `live` cannot collide on one route. */
  prefix: string;
  /** What the chain is for inside that spec. Names the chain and its route. */
  what: string;
  parentId: string;
  /** The marker header, when a case has to tell one deployed version of the chain from another. */
  marker?: string;
  externalRoute?: boolean;
}

/**
 * The same chain, named and routed off the run token.
 *
 * Three specs wrote this wrapper themselves and differed only in the prefix. The name and the
 * context path are the same string on purpose: a route the sweep can find is a route named after
 * the chain that holds it.
 */
export async function tokenizedChain(
  catalog: Catalog,
  run: string,
  options: TokenizedChainOptions,
): Promise<DeployableChain> {
  const name = tokenized(run, `${options.prefix}-${options.what}`);
  return await createDeployableChain(catalog, {
    name,
    parentId: options.parentId,
    contextPath: name,
    marker: options.marker ?? options.what,
    externalRoute: options.externalRoute,
  });
}

/** An empty chain named `name` under the run token, inside `folderId`, so the folder cascade removes it. */
export async function emptyChain(catalog: Catalog, run: string, folderId: string, name: string): Promise<string> {
  return (await catalog.createChain(tokenized(run, name), folderId)).id;
}

/** Changes what the chain answers, so the next snapshot is observably a different one. */
export async function setMarker(
  catalog: Catalog,
  chain: DeployableChain,
  marker: string,
): Promise<void> {
  await catalog.patchElementProperties(chain.id, chain.headerElementId, markerProperties(marker));
}
