import { getConfig } from "../../appConfig";

type UrlLike = string;

export const DOCUMENTATION_ROUTE_BASE = "/doc";
export const DEFAULT_DOCUMENTATION_ASSETS_BASE_URL = "/doc";

export function getDocumentationAssetsBaseUrl(): string {
  return (
    getConfig().documentationBaseUrl || DEFAULT_DOCUMENTATION_ASSETS_BASE_URL
  );
}

function trimTrailingSlashes(s: string): string {
  let end = s.length;
  while (end > 0 && s[end - 1] === "/") end--;
  return s.slice(0, end);
}

export function normalizeBasePath(basePath: string): string {
  const trimmed = basePath.trim();
  if (!trimmed) {
    return "";
  }
  return trimTrailingSlashes(trimmed);
}

export function joinUrl(base: UrlLike, relative: UrlLike): string {
  const b = normalizeBasePath(base);
  const r = relative.startsWith("/") ? relative : `/${relative}`;
  return `${b}${r}`;
}

export function isAbsoluteUrl(url: string): boolean {
  return (
    url.startsWith("http://") ||
    url.startsWith("https://") ||
    url.startsWith("mailto:") ||
    url.startsWith("tel:") ||
    url.startsWith("data:") ||
    url.startsWith("blob:")
  );
}

export function isSafeHref(href: string): boolean {
  const raw = href.trim();
  if (!raw) {
    return false;
  }
  if (raw.startsWith("#") || raw.startsWith("/")) {
    return true;
  }

  // Allow typical relative paths.
  if (!isAbsoluteUrl(raw) && !raw.includes(":")) {
    return true;
  }

  try {
    const parsed = new URL(raw, "https://example.invalid");
    return ["http:", "https:", "mailto:", "tel:", "data:", "blob:"].includes(
      parsed.protocol,
    );
  } catch {
    return false;
  }
}

export function stripLeadingSlashes(s: string): string {
  return s.replace(/^\/+/, "");
}

export function toDocRoutePath(
  routeBase: string,
  docRelativePathNoExt: string,
): string {
  const rel = stripLeadingSlashes(docRelativePathNoExt);
  return joinUrl(routeBase, rel);
}

export function toDocMarkdownAssetPath(docRelativePathNoExt: string): string {
  const raw = docRelativePathNoExt.trim();
  if (!raw) {
    return "";
  }
  const rel = stripLeadingSlashes(raw);
  return rel.endsWith(".md") ? rel : `${rel}.md`;
}

function startsWithDocRoot(s: string): boolean {
  return (
    s.startsWith("01__") ||
    s.startsWith("02__") ||
    s.startsWith("03__") ||
    s.startsWith("04__")
  );
}

function isDocRootSegment(seg: string): boolean {
  return /^0[0-4]__/.test(seg);
}

function isNumPrefixedSegment(seg: string, minNum = 0, maxNum = 9): boolean {
  const m = seg.match(/^(\d+)__(.*)$/);
  if (!m) return false;
  const n = parseInt(m[1], 10);
  return n >= minNum && n <= maxNum;
}

function trimMdExt(s: string): string {
  return s.endsWith(".md") ? s.slice(0, -3) : s;
}

/**
 * Resolves a relative documentation link against the current document path.
 * Handles ".." and "." segments correctly so hrefs like "../../01__Chains/.../doc.md"
 * resolve to the correct absolute doc path (e.g. "01__Chains/.../doc").
 *
 * Paths starting with a doc-root segment (e.g. "01__Chains", "02__Services")
 * are always treated as doc-root-relative, since these top-level folders exist
 * only at documentation root.
 *
 * @param href - Relative link from markdown (e.g. "../../01__Chains/doc.md" or "01__Chains/doc.md")
 * @param currentDocPath - Current document route param (e.g. "00__Overview/1__Token_Processing")
 * @returns Resolved path without .md extension, suitable for doc route
 */
export function resolveDocLink(href: string, currentDocPath: string): string {
  const raw = href.trim().replace(/^\.\//, "");
  if (!raw) {
    return "";
  }
  if (startsWithDocRoot(raw)) {
    return trimTrailingSlashes(trimMdExt(raw));
  }
  // Do NOT add trailing "/" to currentDocPath — the last segment is a file
  // (e.g. "1__Transformation/transformation"), not a directory. Without the
  // trailing slash, URL resolution treats it as a file and "../" goes up
  // from its parent directory, which is the correct behavior.
  const basePath = currentDocPath
    ? `${DOCUMENTATION_ROUTE_BASE}/${currentDocPath}`
    : `${DOCUMENTATION_ROUTE_BASE}/`;
  const base = `https://doc.invalid${basePath.startsWith("/") ? "" : "/"}${basePath}`;
  try {
    const resolved = new URL(raw, base);
    let pathname = resolved.pathname;
    if (pathname.startsWith(`${DOCUMENTATION_ROUTE_BASE}/`)) {
      pathname = pathname.slice(DOCUMENTATION_ROUTE_BASE.length + 1);
    } else if (pathname.startsWith("/")) {
      pathname = pathname.slice(1);
    }
    let result = trimTrailingSlashes(trimMdExt(pathname));
    result = fixDocRootNested(result);
    result = fixChainsSectionSiblingNested(result);
    result = fixFlatChainsSiblingNested(result);
    result = fixTriggersSendersSiblingNested(result);
    result = fixQipSectionSiblings(result);
    result = fixWrongChainCallParent(result);
    result = fixLeafDocWrongParent(result);
    return result;
  } catch {
    return trimMdExt(raw.replace(/^\.\//, ""));
  }
}

function fixDocRootNested(result: string): string {
  let prev = "";
  while (prev !== result) {
    prev = result;
    const segs = result.split("/");
    if (segs.length < 2) break;
    const first = segs[0];
    const second = segs[1];
    if (!isDocRootSegment(first) || !isDocRootSegment(second)) break;
    result = trimTrailingSlashes(segs.slice(1).join("/"));
  }
  return result;
}

function fixChainsSectionSiblingNested(result: string): string {
  if (!result.startsWith("01__Chains/")) return result;
  const rest = result.slice("01__Chains/".length);
  const idx = rest.indexOf("1__Graph/");
  if (idx !== 0 && idx > 0) {
    const before = rest.slice(0, idx - 1);
    if (isNumPrefixedSegment(before, 2, 7)) {
      return `01__Chains/${rest.slice(idx)}`;
    }
  }
  return result;
}

function fixFlatChainsSiblingNested(result: string): string {
  if (!result.startsWith("01__Chains/")) return result;
  const segs = result.slice("01__Chains/".length).split("/");
  if (segs.length < 2) return result;
  const a = segs[0].match(/^([2-7])__(.*)$/);
  const b = segs[1].match(/^([2-7])__(.*)$/);
  if (!a || !b) return result;
  const na = parseInt(a[1], 10);
  const nb = parseInt(b[1], 10);
  if (na <= nb) return result;
  const tail = segs.slice(2).join("/");
  return trimTrailingSlashes(
    `01__Chains/${b[1]}__${b[2]}${tail ? `/${tail}` : ""}`,
  );
}

function fixTriggersSendersSiblingNested(result: string): string {
  const triggersIdx = result.indexOf("/6__Triggers/");
  const sendersIdx = result.indexOf("/7__Senders/");
  const pivot = Math.min(
    triggersIdx >= 0 ? triggersIdx : result.length,
    sendersIdx >= 0 ? sendersIdx : result.length,
  );
  const prefix = result.slice(0, pivot);
  const suffix = result.slice(pivot);
  if (
    !suffix.startsWith("/6__Triggers/") &&
    !suffix.startsWith("/7__Senders/")
  ) {
    return result;
  }
  const afterSection = suffix.indexOf("/", 1) + 1;
  if (afterSection <= 0) return result;
  const sectionRoot = suffix.slice(0, afterSection);
  const rest = suffix.slice(afterSection);
  const parts = rest.split("/");
  if (parts.length < 2) return result;
  const first = parts[0].match(/^([1-8])__(.*)$/);
  const second = parts[1].match(/^([1-8])__(.*)$/);
  if (!first || !second || first[1] === second[1]) return result;
  const tail = parts.slice(2).join("/");
  return trimTrailingSlashes(
    `${prefix}${sectionRoot}${second[1]}__${second[2]}${tail ? `/${tail}` : ""}`,
  );
}

function fixQipSectionSiblings(result: string): string {
  const lib = "/1__QIP_Elements_Library/";
  const idx = result.indexOf(lib);
  if (idx < 0) return result;
  const prefix = result.slice(0, idx + lib.length);
  const rest = result.slice(idx + lib.length);
  const sectionNames = [
    "2__Files",
    "3__Composite_Triggers",
    "4__Services",
    "5__Transformation",
    "6__Triggers",
    "7__Senders",
    "8__Grouping",
  ];
  for (const sec of sectionNames) {
    const bad = `${sec}/1__Routing/`;
    if (rest.startsWith(bad)) {
      return prefix + rest.slice(bad.length);
    }
  }
  return result;
}

function fixWrongChainCallParent(result: string): string {
  const lib = "/1__QIP_Elements_Library/";
  const libIdx = result.indexOf(lib);
  if (libIdx < 0) return result;
  const afterLib = result.slice(libIdx + lib.length);
  if (
    !afterLib.startsWith("6__Triggers/") &&
    !afterLib.startsWith("7__Senders/")
  ) {
    return result;
  }
  const chainCall = "/6__Chain_Call";
  const ccIdx = result.indexOf(chainCall);
  if (ccIdx < 0) return result;
  const beforeCc = result.slice(0, ccIdx);
  const afterCc = result.slice(ccIdx);
  const prefix = beforeCc.slice(0, libIdx + lib.length);
  return `${prefix}1__Routing${chainCall}${afterCc.slice(chainCall.length)}`;
}

function fixLeafDocWrongParent(result: string): string {
  const numPrefixed = /^\d+__/;
  let prev = "";
  while (prev !== result) {
    prev = result;
    const segs = result.split("/");
    for (let i = 0; i < segs.length - 1; i++) {
      const curr = segs[i];
      const next = segs[i + 1];
      if (!numPrefixed.test(curr) && numPrefixed.test(next)) {
        segs.splice(i, 1);
        result = trimTrailingSlashes(segs.join("/"));
        break;
      }
    }
    if (result === prev) break;
  }
  return result;
}
