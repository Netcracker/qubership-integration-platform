import { Element } from "../../api/apiTypes.ts";
import { normalizeProtocol } from "../../misc/protocol-utils.ts";

/** Chain-element property holding the methods a trigger accepts, comma-separated. */
const HTTP_METHOD_RESTRICT = "httpMethodRestrict";

/** Chain-element property naming the protocol a service call speaks. */
const PROTOCOL_TYPE = "integrationOperationProtocolType";

const DEFAULT_HTTP_METHOD = "GET";

function getProperty(element: Element, name: string): unknown {
  return (element.properties as Record<string, unknown> | undefined)?.[name];
}

export function isHttpTrigger(element: Element): boolean {
  return element.type === "http-trigger";
}

/** Endpoints a mock can answer for: HTTP senders and service calls over HTTP. */
export function isHttpEndpoint(element: Element): boolean {
  if (element.type === "http-sender") {
    return true;
  }
  return (
    element.type === "service-call" &&
    normalizeProtocol(getProperty(element, PROTOCOL_TYPE) as string) === "http"
  );
}

/** The elements and their children, depth first: pickers reach nested ones too. */
export function flattenElements(elements: Element[]): Element[] {
  return elements.flatMap((element) => [
    element,
    ...flattenElements(element.children ?? []),
  ]);
}

/** Methods the trigger accepts. A trigger without the property accepts GET. */
export function getHttpMethods(element?: Element): string[] {
  const restrict = element
    ? getProperty(element, HTTP_METHOD_RESTRICT)
    : undefined;
  const methods =
    typeof restrict === "string"
      ? restrict
          .split(",")
          .map((method) => method.trim())
          .filter((method) => method !== "")
      : [];
  return methods.length > 0 ? methods : [DEFAULT_HTTP_METHOD];
}
