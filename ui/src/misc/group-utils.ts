// Validation patterns for chain group paths (mirrors the `metaInfo.group`
// pattern from the QIP schemas). Forbidden characters in a segment: / : * ? " < > | , ; \

// A single path segment (one folder name).
export const GROUP_SEGMENT_REGEX = /^[^/:*?"<>|,;\\]+$/;

// A full group path, e.g. "a/b/c".
export const GROUP_PATH_REGEX = /^([^/:*?"<>|,;\\]+)(\/[^/:*?"<>|,;\\]+)*$/;

// Forbidden characters in a single segment, for sanitization.
const FORBIDDEN_SEGMENT_CHARS = /[/:*?"<>|,;\\]/g;

// Split a group path into non-empty, trimmed segments (tolerates leading, trailing,
// and repeated slashes).
export function parseGroupSegments(group: string): string[] {
  return group
    .split("/")
    .map((segment) => segment.trim())
    .filter((segment) => segment.length > 0);
}

// Replace forbidden characters in a segment with "-".
export function sanitizeGroupSegment(segment: string): string {
  return segment.replace(FORBIDDEN_SEGMENT_CHARS, "-");
}
