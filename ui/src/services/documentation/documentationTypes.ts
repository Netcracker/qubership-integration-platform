export interface DocumentMappingRule {
  pattern: string;
  doc: string;
}

export interface TableOfContentNode {
  title: string;
  documentId?: number;
  children: TableOfContentNode[];
}

export interface SearchResult {
  ref: number;
  score: number;
  /** Tokens actually matched by MiniSearch (may differ from query words due to fuzzy/prefix matching). */
  terms: string[];
}

export type HighlightSegment = {
  text: string;
  isHit: boolean;
};

export interface SearchDetail {
  ref: number;
  title: string;
  body: string;
}
