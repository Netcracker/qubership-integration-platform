import { getConfig } from "../../appConfig";

type UrlLike = string;

export const DOCUMENTATION_ROUTE_BASE = "/doc";
export const DEFAULT_DOCUMENTATION_ASSETS_BASE_URL = "/doc";

export function getDocumentationAssetsBaseUrl(): string {
  return (
    getConfig().documentationBaseUrl || DEFAULT_DOCUMENTATION_ASSETS_BASE_URL
  );
}

export function normalizeBasePath(basePath: string): string {
  const trimmed = basePath.trim();
  if (!trimmed) {
    return "";
  }
  return trimmed.replace(/\/+$/, "");
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
