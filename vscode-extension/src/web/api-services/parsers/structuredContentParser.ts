import * as yaml from "yaml";

/**
 * Parse specification content as JSON first, then YAML. Throws if neither
 * parses. Shared by ContentParser and SpecificationTypeDetector so the
 * import path and the per-format parsers stay in sync on parse options.
 */
export function parseStructuredContent(content: string): any {
  try {
    return JSON.parse(content);
  } catch {
    try {
      return yaml.parse(content, { maxAliasCount: -1 });
    } catch {
      throw new Error("Failed to parse content: not valid JSON or YAML");
    }
  }
}
