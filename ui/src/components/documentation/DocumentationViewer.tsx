import React, { useContext } from "react";
import { useNavigate } from "react-router-dom";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { MermaidDiagram } from "@lightenna/react-mermaid-diagram";
import { useSyntaxHighlighterTheme } from "../../hooks/useSyntaxHighlighterTheme";
import {
  getDocumentationAssetsBaseUrl,
  DOCUMENTATION_ROUTE_BASE,
  isAbsoluteUrl,
  isSafeHref,
  resolveDocLink,
} from "../../services/documentation/documentationUrlUtils";
import { ThemeContext } from "../../theme/context";
import styles from "./DocumentationViewer.module.css";

/**
 * Props for the DocumentationViewer component.
 *
 * @example
 * // With custom path normalizers
 * <DocumentationViewer
 *   content={markdown}
 *   pathNormalizers={[/^OldPrefix\//, /^Legacy_\d+\//]}
 * />
 */
interface DocumentationViewerProps {
  /** Markdown content to render */
  content: string;
  /** Base URL for assets, defaults to config value */
  baseUrl?: string;
  /** Current document path, e.g. "01__Chains/chains" */
  docPath?: string;
  /** Optional RegExp patterns to clean up legacy paths in documentation */
  pathNormalizers?: RegExp[];
}

// Pre-compiled regex for legacy path prefix removal (avoids recompilation on every render).
// Matches: "Helper [anything]/", "N. Helper [anything]/", "N.%20Helper%20[anything]/"
const LEGACY_HELPER_PREFIX_RE = /^(?:\d+\.?(?:%20|\s))?Helper(?:%20|\s)[^/]*\//;

// Matches doc-root-relative paths like "00__Overview/..."
const DOC_ROOT_PATH_RE = /^\d{2}__/;

// Static schema for rehype-sanitize — does not depend on component props/state.
const sanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...(defaultSchema.attributes ?? {}),
    a: [
      ...((defaultSchema.attributes?.a as string[] | undefined) ?? []),
      "target",
      "rel",
    ],
    img: [
      ...((defaultSchema.attributes?.img as string[] | undefined) ?? []),
      "src",
      "alt",
      "title",
    ],
    code: [
      ...((defaultSchema.attributes?.code as string[] | undefined) ?? []),
      "className",
    ],
    span: [
      ...((defaultSchema.attributes?.span as string[] | undefined) ?? []),
      "className",
    ],
  },
} as const;

export const DocumentationViewer: React.FC<DocumentationViewerProps> = ({
  content,
  baseUrl,
  docPath,
  pathNormalizers = [],
}) => {
  const themeContext = useContext(ThemeContext);
  const isDark = themeContext
    ? themeContext.theme === "dark" || themeContext.theme === "high-contrast"
    : false;
  const navigate = useNavigate();
  const syntaxTheme = useSyntaxHighlighterTheme();
  const effectiveAssetsBaseUrl = baseUrl || getDocumentationAssetsBaseUrl();
  const docDir = (() => {
    const raw = (docPath ?? "").replace(/^\/+/, "");
    if (!raw) {
      return "";
    }
    const idx = raw.lastIndexOf("/");
    return idx >= 0 ? raw.slice(0, idx) : "";
  })();

  const normalizePath = (src: string): string => {
    let result = src;
    result = result.replace(LEGACY_HELPER_PREFIX_RE, "");

    for (const pattern of pathNormalizers) {
      result = result.replace(pattern, "");
    }

    return result;
  };

  const resolveDocAssetUrl = (src: string): string => {
    if (!src) {
      return src;
    }

    // Keep absolute/external URLs untouched.
    if (
      src.startsWith("http://") ||
      src.startsWith("https://") ||
      src.startsWith("data:") ||
      src.startsWith("blob:") ||
      src.startsWith("/")
    ) {
      return src;
    }

    const normalized = normalizePath(src).replace(/^\.\//, "");

    // If src already points to a docs-relative path like "00__Overview/.../img/x.svg"
    if (DOC_ROOT_PATH_RE.test(normalized)) {
      return encodeURI(`${effectiveAssetsBaseUrl}/${normalized}`);
    }

    // Resolve as relative to current document folder (handles "img/...", plain filenames, etc.)
    return encodeURI(
      docDir
        ? `${effectiveAssetsBaseUrl}/${docDir}/${normalized}`
        : `${effectiveAssetsBaseUrl}/${normalized}`,
    );
  };

  return (
    <div className={`${styles.viewer}${isDark ? ` ${styles.dark}` : ""}`}>
      <Markdown
        rehypePlugins={[rehypeRaw, [rehypeSanitize, sanitizeSchema]]}
        remarkPlugins={[remarkGfm]}
        components={{
          img({ node: _, ...imgProps }) {
            // Handle Mermaid diagrams
            if (
              imgProps.src?.endsWith(".mermaid") ||
              imgProps.alt === "mermaid"
            ) {
              return (
                <MermaidDiagram theme={isDark ? "dark" : "default"}>
                  {imgProps.src || imgProps.alt || ""}
                </MermaidDiagram>
              );
            }
            const resolvedSrc = imgProps.src
              ? resolveDocAssetUrl(imgProps.src)
              : undefined;
            return (
              <img
                {...imgProps}
                src={resolvedSrc}
                alt={imgProps.alt || "image"}
              />
            );
          },
          code({ node: _, className, children, ...rest }) {
            const langMatch = /language-(\w+)/.exec(className || "");
            const text = Array.isArray(children)
              ? children.join("")
              : `${children as string}`;
            const isInline = !text.includes("\n");
            if (!isInline) {
              return (
                <SyntaxHighlighter
                  style={syntaxTheme}
                  language={langMatch?.[1] || "text"}
                  PreTag="div"
                >
                  {text.replace(/\n$/, "")}
                </SyntaxHighlighter>
              );
            }
            return (
              <code className={className} {...rest}>
                {children}
              </code>
            );
          },
          a({ node: _, ...aProps }) {
            const hrefValue = aProps.href ?? "";
            if (hrefValue && !isSafeHref(hrefValue)) {
              return <span>{aProps.children}</span>;
            }
            const isRelativeDocLink =
              hrefValue &&
              !hrefValue.startsWith("#") &&
              !hrefValue.startsWith("/") &&
              !isAbsoluteUrl(hrefValue);
            if (isRelativeDocLink) {
              const normalizedHref = normalizePath(hrefValue).replace(
                /^docs\//,
                "",
              );
              const resolved = resolveDocLink(normalizedHref, docPath ?? "");
              const href = `${DOCUMENTATION_ROUTE_BASE}/${resolved}`;
              return (
                <a
                  {...aProps}
                  href={href}
                  onClick={(e) => {
                    e.preventDefault();
                    void navigate(href);
                  }}
                >
                  {aProps.children}
                </a>
              );
            }
            // Keep absolute URLs and anchors as-is.
            return (
              <a
                {...aProps}
                target={hrefValue.startsWith("http") ? "_blank" : aProps.target}
                rel={
                  hrefValue.startsWith("http")
                    ? "noopener noreferrer"
                    : aProps.rel
                }
              >
                {aProps.children}
              </a>
            );
          },
        }}
      >
        {content}
      </Markdown>
    </div>
  );
};
