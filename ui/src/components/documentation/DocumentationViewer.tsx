/* eslint-disable react/prop-types */
import React from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import { MermaidDiagram } from '@lightenna/react-mermaid-diagram';
import {
  DOCUMENTATION_ASSETS_BASE_URL,
  DOCUMENTATION_ROUTE_BASE,
  isAbsoluteUrl,
  isSafeHref,
} from '../../services/documentation/documentationUrlUtils';
import { useVSCodeTheme } from '../../hooks/useVSCodeTheme';

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

export const DocumentationViewer: React.FC<DocumentationViewerProps> = ({
  content,
  baseUrl,
  docPath,
  pathNormalizers = [],
}) => {
  const { isDark } = useVSCodeTheme();
  const effectiveAssetsBaseUrl = baseUrl || DOCUMENTATION_ASSETS_BASE_URL;
  const effectiveRouteBase = DOCUMENTATION_ROUTE_BASE;
  const docDir = (() => {
    const raw = (docPath ?? '').replace(/^\/+/, '');
    if (!raw) {
      return '';
    }
    const idx = raw.lastIndexOf('/');
    return idx >= 0 ? raw.slice(0, idx) : '';
  })();

  const normalizePath = (src: string): string => {
    let result = src;
    
    // Remove common legacy prefixes that may appear in documentation from various sources.
    // Pattern matches: "Helper [anything]/", "N. Helper [anything]/", "N.%20Helper%20[anything]/"
    // Examples: "Helper QIP/", "1. Helper /", "3.%20Helper%20Platform%20(D)/"
    result = result.replace(/^(?:\d+\.?(?:%20|\s))?Helper(?:%20|\s)[^/]*\//, '');
    
    // Apply custom normalizers if provided by the library consumer
    for (const pattern of pathNormalizers) {
      result = result.replace(pattern, '');
    }
    
    return result;
  };

  const resolveDocAssetUrl = (src: string): string => {
    if (!src) {
      return src;
    }

    // Keep absolute/external URLs untouched.
    if (
      src.startsWith('http://') ||
      src.startsWith('https://') ||
      src.startsWith('data:') ||
      src.startsWith('blob:') ||
      src.startsWith('/')
    ) {
      return src;
    }

    const normalized = normalizePath(src).replace(/^\.\//, '');

    // If src already points to a docs-relative path like "00__Overview/.../img/x.svg"
    if (/^\d{2}__/.test(normalized) || normalized.startsWith('docs/')) {
      const withoutDocsPrefix = normalized.startsWith('docs/')
        ? normalized.slice('docs/'.length)
        : normalized;
      return encodeURI(`${effectiveAssetsBaseUrl}/docs/${withoutDocsPrefix}`);
    }

    // Typical case: "img/..." relative to current document folder.
    if (normalized.startsWith('img/')) {
      return encodeURI(
        docDir
          ? `${effectiveAssetsBaseUrl}/docs/${docDir}/${normalized}`
          : `${effectiveAssetsBaseUrl}/docs/${normalized}`,
      );
    }

    // Fallback: resolve as relative to current document folder.
    return encodeURI(
      docDir
        ? `${effectiveAssetsBaseUrl}/docs/${docDir}/${normalized}`
        : `${effectiveAssetsBaseUrl}/docs/${normalized}`,
    );
  };

  const sanitizeSchema = {
    ...defaultSchema,
    attributes: {
      ...(defaultSchema.attributes ?? {}),
      a: [...((defaultSchema.attributes?.a as string[] | undefined) ?? []), 'target', 'rel'],
      img: [...((defaultSchema.attributes?.img as string[] | undefined) ?? []), 'src', 'alt', 'title'],
      code: [...((defaultSchema.attributes?.code as string[] | undefined) ?? []), 'className'],
      span: [...((defaultSchema.attributes?.span as string[] | undefined) ?? []), 'className'],
    },
  } as const;

  return (
    <div 
      className="documentation-viewer"
      style={{ 
        display: 'flex',
        flexDirection: 'column',
        padding: '12px 24px 0 24px', 
        width: '100%',
        maxWidth: '1200px', 
        margin: '0 auto',
        boxSizing: 'border-box',
        color: 'var(--vscode-foreground, rgba(0, 0, 0, 0.88))',
        background: 'transparent',
      }}>
      <style>
        {`
          /* Invert monochrome SVG icons in dark theme for better visibility */
          ${isDark ? `
            .documentation-viewer img[src*="/img/"][src$=".svg"] {
              filter: invert(1) hue-rotate(180deg);
            }
          ` : ''}
        `}
      </style>
      <Markdown
        rehypePlugins={[rehypeRaw, [rehypeSanitize, sanitizeSchema]]}
        remarkPlugins={[remarkGfm]}
        components={{
          img(props) {
            // Handle Mermaid diagrams
            if (props.src?.endsWith('.mermaid') || props.alt === 'mermaid') {
              return (
                <MermaidDiagram>
                  {props.src || props.alt || ''}
                </MermaidDiagram>
              );
            }
            const resolvedSrc = props.src ? resolveDocAssetUrl(props.src) : undefined;
            return (
              <img
                {...props}
                src={resolvedSrc}
                alt={props.alt || 'image'}
              />
            );
          },
          a(props) {
            const hrefValue = props.href ?? '';
            if (hrefValue && !isSafeHref(hrefValue)) {
              return <span>{props.children}</span>;
            }
            const isRelativeDocLink =
              hrefValue &&
              !hrefValue.startsWith('#') &&
              !hrefValue.startsWith('/') &&
              !isAbsoluteUrl(hrefValue);
            if (isRelativeDocLink) {
              const href = `${effectiveRouteBase}/${hrefValue}`;
              return (
                <a {...props} href={href} target="_blank" rel="noopener noreferrer">
                  {props.children}
                </a>
              );
            }
            // Keep absolute URLs and anchors as-is.
            return (
              <a {...props} target={hrefValue.startsWith('http') ? '_blank' : props.target} rel={hrefValue.startsWith('http') ? 'noopener noreferrer' : props.rel}>
                {props.children}
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
