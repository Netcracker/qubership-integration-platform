/* eslint-disable
  @typescript-eslint/no-unsafe-argument,
  @typescript-eslint/no-unsafe-assignment,
  @typescript-eslint/no-unsafe-call,
  @typescript-eslint/no-unsafe-member-access,
  @typescript-eslint/no-unsafe-return
*/
import type {
  DocumentMappingRule,
  TableOfContentNode,
  SearchResult,
  HighlightSegment,
} from './documentationTypes';
import {
  DOCUMENTATION_ASSETS_BASE_URL,
  DOCUMENTATION_ROUTE_BASE,
  joinUrl,
} from './documentationUrlUtils';
import {
  formatFragmentSegments,
  segmentsToSafeHtml,
  extractWords,
} from './documentationHighlightUtils';

import type elasticlunr from 'elasticlunr';

type ElasticlunrModule = typeof elasticlunr;

export class DocumentationService {
  private readonly MAX_FOUND_DOCUMENT_FRAGMENTS = 3;
  private pathsPromise: Promise<string[]> | null = null;
  private namesPromise: Promise<string[][]> | null = null;
  private tocPromise: Promise<TableOfContentNode> | null = null;
  private searchIndexPromise: Promise<
    elasticlunr.Index<{ id: number; title: string; body: string }>
  > | null = null;
  private contextMappingPromise: Promise<DocumentMappingRule[]> | null = null;
  private elementMappingPromise: Promise<DocumentMappingRule[]> | null = null;
  private elasticlunrPromise: Promise<ElasticlunrModule> | null = null;
  private elementTypeMappingCache: Record<string, string> | null = null;

  private async getElasticlunr(): Promise<ElasticlunrModule> {
    if (!this.elasticlunrPromise) {
      this.elasticlunrPromise = (async () => {
        const lunrModule = await import('lunr');
        const lunr = lunrModule.default ?? lunrModule;
        (globalThis as Record<string, unknown>).lunr = lunr;

        const elasticlunrModule = await import('elasticlunr');
        return elasticlunrModule.default ?? elasticlunrModule;
      })();
    }
    return this.elasticlunrPromise;
  }

  private getDocRoot(): string {
    return DOCUMENTATION_ASSETS_BASE_URL;
  }

  private loadResource<T>(path: string): Promise<T> {
    const docRoot = this.getDocRoot();
    const fullPath = joinUrl(docRoot, path.startsWith('/') ? path : `/${path}`);

    return fetch(fullPath)
      .then((response) => {
        if (!response.ok) {
          throw new Error(`Failed to load ${fullPath}: ${response.statusText}`);
        }
        return response.json();
      })
      .then((data) => data as T);
  }

  public loadPaths(): Promise<string[]> {
    if (!this.pathsPromise) {
      this.pathsPromise = this.loadResource<string[]>('paths.json');
    }
    return this.pathsPromise;
  }

  public loadNames(): Promise<string[][]> {
    if (!this.namesPromise) {
      this.namesPromise = this.loadResource<string[][]>('names.json');
    }
    return this.namesPromise;
  }

  public loadTOC(): Promise<TableOfContentNode> {
    if (!this.tocPromise) {
      this.tocPromise = this.loadResource<TableOfContentNode>('toc.json');
    }
    return this.tocPromise;
  }

  public loadSearchIndex(): Promise<
    elasticlunr.Index<{ id: number; title: string; body: string }>
  > {
    if (!this.searchIndexPromise) {
      this.searchIndexPromise = this.loadResource<unknown>('search-index.json').then(
        async (indexDump) => {
          const elasticlunr = await this.getElasticlunr();
          return elasticlunr.Index.load(indexDump);
        },
      );
    }
    return this.searchIndexPromise;
  }

  public loadContextMapping(): Promise<DocumentMappingRule[]> {
    if (!this.contextMappingPromise) {
      this.contextMappingPromise = this.loadResource<DocumentMappingRule[]>(
        'context-doc-mapping.json'
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
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  /**
   * Builds element type to documentation path mapping automatically from paths.json.
   * Extracts element types from file names and folder names in QIP_Elements_Library paths.
   */
  private async buildElementTypeMapping(): Promise<Record<string, string>> {
    if (this.elementTypeMappingCache) {
      return this.elementTypeMappingCache;
    }

    const paths = await this.loadPaths();
    const autoMapping: Record<string, string> = {};
    
    // Auto-generate mapping from file/folder names
    paths.forEach((path) => {
      if (!path.includes('QIP_Elements_Library')) return;
      
      const parts = path.split('/');
      const fileName = parts[parts.length - 1].replace('.md', '');
      const folderName = parts[parts.length - 2];
      
      // Extract element types from both file name and folder name
      const elementTypes: string[] = [];
      
      // From file name: "http_trigger" → "http-trigger"
      if (fileName && fileName !== 'index') {
        elementTypes.push(fileName.replace(/_/g, '-'));
      }
      
      // From folder name: "1__HTTP_Trigger" → "http-trigger"
      const folderPart = folderName.split('__')[1];
      if (folderPart) {
        elementTypes.push(
          folderPart
            .replace(/_/g, '-')
            .toLowerCase()
        );
      }
      
      // Register all variants
      const docPath = `/doc/${path.replace('.md', '')}`;
      elementTypes.forEach((type) => {
        if (type && !autoMapping[type]) {
          autoMapping[type] = docPath;
        }
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
  private getElementTypeAliases(baseMapping: Record<string, string>): Record<string, string> {
    const aliases: Record<string, string> = {};
    
    // Condition element has multiple IDs
    if (baseMapping['condition']) {
      aliases['else'] = baseMapping['condition'];
      aliases['if'] = baseMapping['condition'];
    }
    
    // Try-Catch-Finally element
    if (baseMapping['try-catch-finally']) {
      aliases['try'] = baseMapping['try-catch-finally'];
      aliases['catch'] = baseMapping['try-catch-finally'];
      aliases['finally'] = baseMapping['try-catch-finally'];
    }
    
    // Headers modification variations
    if (baseMapping['headers-modification']) {
      aliases['header-modification'] = baseMapping['headers-modification'];
    }
    
    // AsyncAPI trigger variations
    if (baseMapping['asyncapi-trigger']) {
      aliases['async-api-trigger'] = baseMapping['asyncapi-trigger'];
    }
    
    // Scheduler alias
    if (baseMapping['scheduler']) {
      aliases['quartz'] = baseMapping['scheduler'];
      aliases['quartz-scheduler'] = baseMapping['scheduler'];
    }
    
    return aliases;
  }

  public async search(query: string): Promise<SearchResult[]> {
    const searchConfiguration = {
      fields: {
        title: { boost: 2, bool: 'AND' as const, expand: false },
        body: { boost: 1, bool: 'OR' as const, expand: false },
      },
    };
    const index = await this.loadSearchIndex();
    const raw = index.search(query, searchConfiguration) as unknown as Array<{
      ref: string;
      score: number;
    }>;
    return raw
      .map((r) => ({ ref: Number.parseInt(r.ref, 10), score: r.score }))
      .filter((r) => Number.isFinite(r.ref));
  }

  public async getSearchDetail(
    ref: number,
    query: string
  ): Promise<string[]> {
    const segments = await this.getSearchDetailSegments(ref, query);
    return segments.map((fragment) => segmentsToSafeHtml(fragment));
  }

  public async getSearchDetailSegments(
    ref: number,
    query: string
  ): Promise<HighlightSegment[][]> {
    const elasticlunr = await this.getElasticlunr();
    const index = await this.loadSearchIndex();
    const doc = index.documentStore.getDoc(ref.toString());
    if (!doc) {
      return [];
    }

    const paragraphs = this.extractParagraphs(doc.body);
    const paragraphIndex = this.createParagraphSearchIndex(elasticlunr, paragraphs);
    return this.searchInParagraphs(elasticlunr, paragraphIndex, paragraphs, query)
      .slice(0, this.MAX_FOUND_DOCUMENT_FRAGMENTS)
      .sort((r0, r1) => parseInt(r0.ref) - parseInt(r1.ref))
      .map((searchResult) => {
        const par = paragraphIndex.documentStore.getDoc(searchResult.ref);
        return par?.body ?? '';
      })
      .filter((t) => t.length > 0)
      .map((text) => this.formatFoundDocumentFragmentSegments(elasticlunr, text, query));
  }

  private extractParagraphs(text: string): string[] {
    return text.split(/\n\n+/).filter((par) => extractWords(par).length > 1);
  }

  private createParagraphSearchIndex(
    elasticlunr: ElasticlunrModule,
    paragraphs: string[],
  ): elasticlunr.Index<{ id: number; body: string }> {
    const index = elasticlunr<{ id: number; body: string }>();
    index.setRef('id');
    index.addField('body');
    index.saveDocument(true);
    paragraphs.forEach((p, i) => index.addDoc({ id: i, body: p }));
    return index;
  }

  private searchInParagraphs(
    elasticlunr: typeof import('elasticlunr'),
    index: elasticlunr.Index<{ id: number; body: string }>,
    paragraphs: string[],
    query: string
  ): Array<{ ref: string; score: number }> {
    const searchConfiguration = {
      fields: { body: { boost: 1, bool: 'OR' as const, expand: false } },
    };
    const result = index.search(query, searchConfiguration) as unknown as Array<{
      ref: string;
      score: number;
    }>;
    if (result.length) {
      return result;
    }
    const words = extractWords(query).map((word) =>
      elasticlunr.stemmer(word.toLowerCase())
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
    elasticlunr: ElasticlunrModule,
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
  private async mapPathByElementType(elementType: string): Promise<string> {
    const mapping = await this.buildElementTypeMapping();
    const docPath = mapping[elementType];
    
    if (docPath) {
      console.log('Documentation mapping:', elementType, '->', docPath);
      return docPath;
    }
    
    console.log('Documentation mapping not found for:', elementType);
    const routeBase = DOCUMENTATION_ROUTE_BASE;
    return `${routeBase}/not-found`;
  }

  public async openChainElementDocumentation(elementType: string): Promise<void> {
    console.log('Opening', elementType, 'chain element documentation...');
    try {
      const docPath = await this.mapPathByElementType(elementType);
      this.openPage(docPath);
    } catch (error) {
      console.error('Failed to open element documentation:', error);
      // Fallback to documentation home
      const routeBase = DOCUMENTATION_ROUTE_BASE;
      this.openPage(routeBase);
    }
  }

  public openContextDocumentation(): void {
    console.log('Opening context documentation...');
    const path = window.location.pathname + window.location.hash;
    void this.openMappedDocumentation(this.loadContextMapping(), path).catch(() => {
      // Fallback to documentation home if context mapping fails
      const routeBase = DOCUMENTATION_ROUTE_BASE;
      this.openPage(routeBase);
    });
  }

  public openPage(url: string): void {
    window.open(url, '_blank');
  }

  private async openMappedDocumentation(
    mappingRules: Promise<DocumentMappingRule[]>,
    path: string
  ): Promise<void> {
    try {
      const rules = await mappingRules;
      const mappedPath = this.mapPath(rules, path);
      this.openPage(mappedPath);
    } catch (error) {
      console.error('Failed to open documentation:', error);
      // Re-throw to allow caller to handle fallback
      throw error;
    }
  }

  private mapPath(mappingRules: DocumentMappingRule[], path: string): string {
    const mappingRule = mappingRules.find(
      (rule) => new RegExp(rule.pattern).test(path)
    );
    if (mappingRule) {
      console.log('Documentation mapping rule:', mappingRule);
    } else {
      console.log('Documentation mapping rule not found');
    }
    const routeBase = DOCUMENTATION_ROUTE_BASE;
    const mappedPath = mappingRule?.doc || `${routeBase}/not-found`;
    console.log('Documentation mapping:', path, '->', mappedPath);
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
    return paths.length ? paths[0] : '';
  }
}

export const documentationService = new DocumentationService();
