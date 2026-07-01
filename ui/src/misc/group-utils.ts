// Validation pattern and parsing for chain group paths (mirrors the
// `metaInfo.group` pattern from the QIP schemas).
// Forbidden characters in a segment: / : * ? " < > | , ; \

// A single path segment (one folder name).
export const GROUP_SEGMENT_REGEX = /^[^/:*?"<>|,;\\]+$/;

// Split a group path into non-empty, trimmed segments (tolerates leading, trailing,
// and repeated slashes).
export function parseGroupSegments(group: string): string[] {
  return group
    .split("/")
    .map((segment) => segment.trim())
    .filter((segment) => segment.length > 0);
}
