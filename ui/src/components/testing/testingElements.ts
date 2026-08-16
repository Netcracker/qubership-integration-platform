import { Element } from "../../api/apiTypes.ts";

/** Chain-element property holding the methods a trigger accepts, comma-separated. */
const HTTP_METHOD_RESTRICT = "httpMethodRestrict";

const DEFAULT_HTTP_METHOD = "GET";

export function isHttpTrigger(element: Element): boolean {
  return element.type === "http-trigger";
}

/** Methods the trigger accepts. A trigger without the property accepts GET. */
export function getHttpMethods(element?: Element): string[] {
  const restrict = (
    element?.properties as Record<string, unknown> | undefined
  )?.[HTTP_METHOD_RESTRICT];
  const methods =
    typeof restrict === "string"
      ? restrict
          .split(",")
          .map((method) => method.trim())
          .filter((method) => method !== "")
      : [];
  return methods.length > 0 ? methods : [DEFAULT_HTTP_METHOD];
}
