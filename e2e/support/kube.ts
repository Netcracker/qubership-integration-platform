/**
 * Reads the resources the catalog and the engine write to namespace `qip-e2e`: camel-k
 * Integrations, ConfigMaps, HTTPRoutes, and Services, and builds the addresses that reach them
 * through the proxy's e2e locations.
 *
 * Only `specs/k8s/` imports this module. A spec anywhere else runs on both targets and reaches the
 * platform through `Env`; a spec in `specs/k8s/` asserts the Kubernetes resources themselves, which
 * no other target has.
 */
import { proxyUrl } from "../env/containers.js";
import {
  deploymentOf,
  kubectl,
  microChainUrl,
  microServiceOf,
  NAMESPACE,
  proxiedChainUrl,
  proxiedServiceUrl,
} from "../env/k8s.js";

export { microChainUrl, microServiceOf, proxiedChainUrl, proxiedServiceUrl };

/** The classic engine's Service, which carries its Deployment's name. */
export const CLASSIC_ENGINE_SERVICE = deploymentOf("engine");

/** The HTTPRoute holding every external trigger the classic engine serves on the public gateway. */
export const CLASSIC_PUBLIC_ROUTES = `${CLASSIC_ENGINE_SERVICE}-chain-public-routes`;

/** The gateway's path prefix for a chain with an external trigger. */
export const GATEWAY_ROUTE_PREFIX = "/qip-routes";

export interface KubeObject {
  metadata: { name: string; labels?: Record<string, string> };
}

export interface HttpRoute extends KubeObject {
  spec: {
    parentRefs: { kind?: string; name: string }[];
    rules: {
      matches: { path: { type: string; value: string } }[];
      backendRefs?: { kind?: string; name: string; port?: number }[];
    }[];
  };
}

export interface Integration extends KubeObject {
  status?: { phase?: string };
}

const INTEGRATIONS = "integrations.camel.apache.org";
const HTTP_ROUTES = "httproutes.gateway.networking.k8s.io";

async function getOne<T>(kind: string, name: string): Promise<T | null> {
  const out = await kubectl(["get", kind, name, "-n", NAMESPACE, "-o", "json", "--ignore-not-found"]);
  return out.trim() ? (JSON.parse(out) as T) : null;
}

async function list<T>(kind: string, selector?: string): Promise<T[]> {
  const args = ["get", kind, "-n", NAMESPACE, "-o", "json"];
  if (selector) args.push("-l", selector);
  return (JSON.parse(await kubectl(args)) as { items: T[] }).items;
}

/** The Integration named `name`, or `null` once it is gone. */
export function integration(name: string): Promise<Integration | null> {
  return getOne(INTEGRATIONS, name);
}

/** The HTTPRoute named `name`, or `null` when there is none. */
export function httpRoute(name: string): Promise<HttpRoute | null> {
  return getOne(HTTP_ROUTES, name);
}

/** The ConfigMaps matching a label selector, such as `qip-domain=<domain>`. */
export function configMaps(selector: string): Promise<KubeObject[]> {
  return list("configmaps", selector);
}

/**
 * An id as the resource build writes it into a name or a label: `K8sNameValidator.validate` keeps
 * lowercase letters, digits, and dashes, drops one leading digit or dash, and cuts at 63 characters.
 * A snapshot id that starts with a digit is therefore not its own label value.
 */
export function k8sName(id: string): string {
  const kept = id.replace(/[^-a-z0-9]/g, "");
  return (/^[-0-9]/.test(kept) ? kept.slice(1) : kept).slice(0, 63);
}

/** The names of every Service in the namespace. */
export async function serviceNames(): Promise<string[]> {
  return (await list<KubeObject>("services")).map((each) => each.metadata.name);
}

/** The public gateway, through the proxy's `/e2e/gateway/` location. */
export function gatewayUrl(path: string): string {
  return `${proxyUrl()}/e2e/gateway${path}`;
}
