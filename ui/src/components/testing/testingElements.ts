import { Element } from "../../api/apiTypes.ts";
import { normalizeProtocol } from "../../misc/protocol-utils.ts";

/** Chain-element property holding the methods a trigger accepts, comma-separated. */
const HTTP_METHOD_RESTRICT = "httpMethodRestrict";

/** Chain-element property naming the protocol a service call speaks. */
const PROTOCOL_TYPE = "integrationOperationProtocolType";

const DEFAULT_HTTP_METHOD = "GET";

/**
 * Methods a test case can be stored with. A trigger may also accept OPTIONS,
 * which the service has no enum value for and PostgreSQL then answers with a
 * 500, so it is dropped before it reaches the Method picker.
 */
const STORABLE_HTTP_METHODS = new Set([
  "GET",
  "POST",
  "PUT",
  "PATCH",
  "DELETE",
  "HEAD",
]);

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

/**
 * Methods the trigger accepts and the service can store. A trigger without the
 * property, or one restricted to methods the service has no value for, accepts GET.
 */
export function getHttpMethods(element?: Element): string[] {
  const restrict = element
    ? getProperty(element, HTTP_METHOD_RESTRICT)
    : undefined;
  const methods =
    typeof restrict === "string"
      ? restrict
          .split(",")
          .map((method) => method.trim().toUpperCase())
          .filter((method) => STORABLE_HTTP_METHODS.has(method))
      : [];
  return methods.length > 0 ? methods : [DEFAULT_HTTP_METHOD];
}
