// Initialize lunr globally BEFORE importing elasticlunr
// This ensures lunr is available when elasticlunr loads
import "../../lunr-init";

import type {
  DocumentMappingRule,
  HighlightSegment,
  SearchResult,
  TableOfContentNode,
} from "./documentationTypes";
import {
  getDocumentationAssetsBaseUrl,
  DOCUMENTATION_ROUTE_BASE,
  joinUrl,
} from "./documentationUrlUtils";
import {
  extractWords,
  formatFragmentSegments,
  segmentsToSafeHtml,
} from "./documentationHighlightUtils";
import { onConfigChange } from "../../appConfig";

import elasticlunr from "elasticlunr";

type Fetcher = (url: string) => Promise<Response>;
type WindowOpener = (url: string, target: string) => Window | null;

/**
 * Configuration for element type aliases.
 * Maps base element types to their alternative names.
 */
const ELEMENT_ALIASES: Record<string, string[]> = {
  condition: ["else", "if"],
  "try-catch-finally": [
    "try",
    "catch",
    "finally",
    "try-2",
    "catch-2",
    "finally-2",
    "try-catch-finally-2",
  ],
  "headers-modification": ["header-modification"],
  "asyncapi-trigger": ["async-api-trigger"],
  scheduler: ["quartz", "quartz-scheduler"],
  "chain-call": ["chain-call-2"],
  "chain-trigger": ["chain-trigger-2"],
  "circuit-breaker": [
    "circuit-breaker-2",
    "circuit-breaker-configuration-2",
    "on-fallback-2",
  ],
  loop: ["loop-2"],
  split: ["split-2", "split-element-2", "main-split-element-2"],
  "split-async": ["split-async-2", "async-split-element-2"],
  "kafka-sender": ["kafka-sender-2"],
  "kafka-trigger": ["kafka-trigger-2"],
  "rabbitmq-sender": ["rabbitmq-sender-2"],
  "rabbitmq-trigger": ["rabbitmq-trigger-2"],
  mapper: ["mapper-2"],
  "sftp-trigger": ["sftp-trigger-2"],
};

export class DocumentationService {
  private readonly MAX_FOUND_DOCUMENT_FRAGMENTS = 3;
  private readonly ELEMENTS_LIBRARY_PATH = "QIP_Elements_Library";
  private readonly INDEX_FILENAME = "index";

  private pathsPromise: Promise<string[]> | null = null;
  private namesPromise: Promise<string[][]> | null = null;
  private tocPromise: Promise<TableOfContentNode> | null = null;
  private searchIndexPromise: Promise<
    elasticlunr.Index<{ id: number; title: string; body: string }>
  > | null = null;
  private contextMappingPromise: Promise<DocumentMappingRule[]> | null = null;
  private elementMappingPromise: Promise<DocumentMappingRule[]> | null = null;
  elementTypeMappingCache: Record<string, string> | null = null;

  constructor(
    private fetcher: Fetcher = (url: string) => fetch(url),
    private windowOpener: WindowOpener = (url: string, target: string) =>
      window.open(url, target),
  ) {}

  public resetCaches(): void {
    this.pathsPromise = null;
    this.namesPromise = null;
    this.tocPromise = null;
    this.searchIndexPromise = null;
    this.contextMappingPromise = null;
    this.elementMappingPromise = null;
    this.elementTypeMappingCache = null;
  }

  private async loadResource<T>(path: string): Promise<T> {
    const fullPath = joinUrl(
      getDocumentationAssetsBaseUrl(),
      path.startsWith("/") ? path : `/${path}`,
    );

    const response = await this.fetcher(fullPath);

    if (!response.ok) {
      throw new Error(`Failed to load ${fullPath}: ${response.statusText}`);
    }

    // Check if response is actually JSON, not HTML from Vite SPA fallback
    const contentType = response.headers.get("content-type");
    if (!contentType?.includes("application/json")) {
      const text = await response.text();
      const isHtmlResponse =
        contentType?.includes("text/html") ||
        text.trim().startsWith("<!DOCTYPE") ||
        text.trim().startsWith("<html") ||
        text.trim().startsWith("<!doctype");

      if (isHtmlResponse) {
        throw new Error(
          `Resource not found: ${fullPath} (received HTML instead of JSON)`,
        );
      }
    }

    const data = await response.json();
    return data as T;
  }

  public loadPaths(): Promise<string[]> {
    if (!this.pathsPromise) {
      this.pathsPromise = this.loadResource<string[]>("paths.json");
    }
    return this.pathsPromise;
  }

  public loadNames(): Promise<string[][]> {
    if (!this.namesPromise) {
      this.namesPromise = this.loadResource<string[][]>("names.json");
    }
    return this.namesPromise;
  }

  public loadTOC(): Promise<TableOfContentNode> {
    if (!this.tocPromise) {
      this.tocPromise = this.loadResource<TableOfContentNode>("toc.json");
    }
    return this.tocPromise;
  }

  public loadSearchIndex(): Promise<
    elasticlunr.Index<{ id: number; title: string; body: string }>
  > {
    if (!this.searchIndexPromise) {
      this.searchIndexPromise = this.loadResource<unknown>(
        "search-index.json",
      ).then((indexDump) => elasticlunr.Index.load(indexDump));
    }
    return this.searchIndexPromise;
  }

  public loadContextMapping(): Promise<DocumentMappingRule[]> {
    if (!this.contextMappingPromise) {
      this.contextMappingPromise = this.loadResource<DocumentMappingRule[]>(
        "context-doc-mapping.json",
      );
    }
    return this.contextMappingPromise;
  }

  public async loadElementMapping(): Promise<DocumentMappingRule[]> {
    if (!this.elementMappingPromise) {
      this.elementMappingPromise = this.buildElementMappingRules();
    }
    return this.elementMappingPromise;
  }

  /**
   * Builds mapping rules from auto-generated element type mapping.
   * Converts Record<string, string> to DocumentMappingRule[] for compatibility.
   */
  private async buildElementMappingRules(): Promise<DocumentMappingRule[]> {
    const mapping = await this.buildElementTypeMapping();

    return Object.entries(mapping).map(([elementType, docPath]) => ({
      pattern: `^${this.escapeRegExp(elementType)}$`, // Exact match pattern
      doc: docPath,
    }));
  }

  /**
   * Escapes special regex characters in a string for use in RegExp.
   */
  private escapeRegExp(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  /**
   * Builds element type to documentation path mapping automatically from paths.json.
   * Extracts element types from file names and folder names in QIP_Elements_Library paths.
   */
  public async buildElementTypeMapping(): Promise<Record<string, string>> {
    if (this.elementTypeMappingCache) {
      return this.elementTypeMappingCache;
    }

    const paths = await this.loadPaths();
    const autoMapping: Record<string, string> = {};

    // Auto-generate mapping from file/folder names
    paths.forEach((path) => {
      if (!path.includes(this.ELEMENTS_LIBRARY_PATH)) return;

      const parts = path.split("/");
      const fileName = parts[parts.length - 1].replace(".md", "");
      const folderName = parts[parts.length - 2];

      // Extract element types from both file name and folder name
      const elementTypes = new Set<string>();

      // From file name: "http_trigger" → "http-trigger"
      if (fileName && fileName !== this.INDEX_FILENAME) {
        elementTypes.add(fileName.replace(/_/g, "-"));
      }

      // From folder name: "1__HTTP_Trigger" → "http-trigger"
      const folderPart = folderName.split("__")[1];
      if (folderPart) {
        elementTypes.add(folderPart.replace(/_/g, "-").toLowerCase());
      }

      // Register all variants (use nullish coalescing for more modern syntax)
      const docPath = `/doc/${path.replace(".md", "")}`;
      elementTypes.forEach((type) => {
        autoMapping[type] ??= docPath;
      });
    });

    // Apply hardcoded aliases for elements with multiple IDs
    const aliases = this.getElementTypeAliases(autoMapping);
    const finalMapping = { ...autoMapping, ...aliases };

    this.elementTypeMappingCache = finalMapping;
    return finalMapping;
  }

  /**
   * Returns hardcoded aliases for elements that have multiple type IDs.
   * These are edge cases where element type doesn't match file/folder name.
   */
  public getElementTypeAliases(
    baseMapping: Record<string, string>,
  ): Record<string, string> {
    const aliases: Record<string, string> = {};

    Object.entries(ELEMENT_ALIASES).forEach(([baseType, aliasNames]) => {
      const docPath = baseMapping[baseType];
      if (docPath) {
        aliasNames.forEach((alias) => {
          aliases[alias] = docPath;
        });
      }
    });

    return aliases;
  }

  public async search(query: string): Promise<SearchResult[]> {
    const searchConfiguration = {
      fields: {
        title: { boost: 2, bool: "AND" as const, expand: false },
        body: { boost: 1, bool: "OR" as const, expand: false },
      },
    };
    const index = await this.loadSearchIndex();
    const raw = index.search(query, searchConfiguration) as Array<{
      ref: string;
      score: number;
    }>;
    return raw
      .map((r) => ({ ref: Number.parseInt(r.ref, 10), score: r.score }))
      .filter((r) => Number.isFinite(r.ref));
  }

  public async getSearchDetail(ref: number, query: string): Promise<string[]> {
    const segments = await this.getSearchDetailSegments(ref, query);
    return segments.map((fragment) => segmentsToSafeHtml(fragment));
  }

  public async getSearchDetailSegments(
    ref: number,
    query: string,
  ): Promise<HighlightSegment[][]> {
    const index = await this.loadSearchIndex();
    const doc = index.documentStore.getDoc(ref.toString());
    if (!doc) {
      return [];
    }

    const paragraphs = this.extractParagraphs(doc.body);
    const paragraphIndex = this.createParagraphSearchIndex(paragraphs);
    return this.searchInParagraphs(paragraphIndex, paragraphs, query)
      .slice(0, this.MAX_FOUND_DOCUMENT_FRAGMENTS)
      .sort((r0, r1) => parseInt(r0.ref) - parseInt(r1.ref))
      .map((searchResult) => {
        const par = paragraphIndex.documentStore.getDoc(searchResult.ref);
        return par?.body ?? "";
      })
      .filter((t) => t.length > 0)
      .map((text) => this.formatFoundDocumentFragmentSegments(text, query));
  }

  private extractParagraphs(text: string): string[] {
    return text.split(/\n\n+/).filter((par) => extractWords(par).length > 1);
  }

  private createParagraphSearchIndex(
    paragraphs: string[],
  ): elasticlunr.Index<{ id: number; body: string }> {
    const index = elasticlunr<{ id: number; body: string }>();
    index.setRef("id");
    index.addField("body");
    index.saveDocument(true);
    paragraphs.forEach((p, i) => index.addDoc({ id: i, body: p }));
    return index;
  }

  private searchInParagraphs(
    index: elasticlunr.Index<{ id: number; body: string }>,
    paragraphs: string[],
    query: string,
  ): Array<{ ref: string; score: number }> {
    const searchConfiguration = {
      fields: { body: { boost: 1, bool: "OR" as const, expand: false } },
    };
    const result = index.search(query, searchConfiguration) as Array<{
      ref: string;
      score: number;
    }>;
    if (result.length) {
      return result;
    }
    const words = extractWords(query).map((word) =>
      elasticlunr.stemmer(word.toLowerCase()),
    );

    // Fallback: stemmed substring scan over source paragraphs.
    return paragraphs
      .map((body, id) => ({ id, body }))
      .filter((p) => {
        const text = p.body.toLowerCase();
        return words.some((w) => text.includes(w));
      })
      .map((p) => ({ ref: p.id.toString(), score: 0 }));
  }

  private formatFoundDocumentFragmentSegments(
    text: string,
    query: string,
  ): HighlightSegment[] {
    return formatFragmentSegments(text, query, (w) => elasticlunr.stemmer(w));
  }

  // NOTE: highlighting helpers are in documentationHighlightUtils.ts for unit testing.

  /**
   * Maps element type directly to documentation path using auto-generated mapping.
   * More efficient than regex-based search.
   */
  public async mapPathByElementType(elementType: string): Promise<string> {
    const mapping = await this.buildElementTypeMapping();
    const docPath = mapping[elementType];

    if (docPath) {
      console.log("Documentation mapping:", elementType, "->", docPath);
      return docPath;
    }

    console.log("Documentation mapping not found for:", elementType);
    return `${DOCUMENTATION_ROUTE_BASE}/not-found`;
  }

  public async openChainElementDocumentation(
    elementType: string,
    onError?: (error: Error) => void,
  ): Promise<void> {
    console.log("Opening", elementType, "chain element documentation...");
    try {
      const docPath = await this.mapPathByElementType(elementType);
      this.openPage(docPath);
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error));
      console.error("Failed to open element documentation:", err);
      onError?.(err);
      // Fallback to documentation home
      this.openPage(DOCUMENTATION_ROUTE_BASE);
    }
  }

  public openContextDocumentation(onError?: (error: Error) => void): void {
    console.log("Opening context documentation...");
    const path = window.location.pathname + window.location.hash;
    void this.openMappedDocumentation(this.loadContextMapping(), path).catch(
      (error) => {
        const err = error instanceof Error ? error : new Error(String(error));
        console.error("Failed to open context documentation:", err);
        onError?.(err);
        // Fallback to documentation home if context mapping fails
        this.openPage(DOCUMENTATION_ROUTE_BASE);
      },
    );
  }

  public openPage(url: string): void {
    this.windowOpener(url, "_blank");
  }

  private async openMappedDocumentation(
    mappingRules: Promise<DocumentMappingRule[]>,
    path: string,
  ): Promise<void> {
    const rules = await mappingRules;
    const mappedPath = this.mapPath(rules, path);
    this.openPage(mappedPath);
  }

  private mapPath(mappingRules: DocumentMappingRule[], path: string): string {
    const mappingRule = mappingRules.find((rule) =>
      new RegExp(rule.pattern).test(path),
    );
    if (mappingRule) {
      console.log("Documentation mapping rule:", mappingRule);
    } else {
      console.log("Documentation mapping rule not found");
    }
    const mappedPath =
      mappingRule?.doc || `${DOCUMENTATION_ROUTE_BASE}/not-found`;
    console.log("Documentation mapping:", path, "->", mappedPath);
    return mappedPath;
  }

  public async mapElementToDoc(elementType: string): Promise<string | null> {
    const rules = await this.loadElementMapping();
    const rule = rules.find((r) => new RegExp(r.pattern).test(elementType));
    return rule?.doc || null;
  }

  public async mapContextToDoc(path: string): Promise<string | null> {
    const rules = await this.loadContextMapping();
    const rule = rules.find((r) => new RegExp(r.pattern).test(path));
    return rule?.doc || null;
  }

  public async getDefaultDocumentPath(): Promise<string> {
    const paths = await this.loadPaths();
    return paths.length ? paths[0] : "";
  }
}

export const documentationService = new DocumentationService();

onConfigChange(() => {
  documentationService.resetCaches();
});
