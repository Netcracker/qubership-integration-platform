import type { HighlightSegment } from './documentationTypes';

export function extractWords(s: string): string[] {
  return s.trim().split(/\W+/).filter((w) => w.length > 0);
}

export function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function escapeHtml(s: string): string {
  return s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

export function highlightSegments(
  text: string,
  query: string,
  stemmer: (word: string) => string,
): HighlightSegment[] {
  const queryWords = new Set(
    extractWords(query).map((word) => stemmer(word.toLowerCase())),
  );

  const wordsToHighlight = Array.from(
    new Set(
      extractWords(text).filter((word) =>
        queryWords.has(stemmer(word.toLowerCase())),
      ),
    ),
  )
    .filter((w) => w.trim().length > 0)
    .sort((a, b) => b.length - a.length);

  if (wordsToHighlight.length === 0) {
    return [{ text, isHit: false }];
  }

  const regex = new RegExp(
    `(${wordsToHighlight.map(escapeRegExp).join('|')})`,
    'gi',
  );

  const segments: HighlightSegment[] = [];
  let lastIndex = 0;

  for (const match of text.matchAll(regex)) {
    const idx = match.index ?? -1;
    if (idx < 0) {
      continue;
    }
    if (idx > lastIndex) {
      segments.push({ text: text.slice(lastIndex, idx), isHit: false });
    }
    const value = match[0] ?? '';
    if (value) {
      segments.push({ text: value, isHit: true });
    }
    lastIndex = idx + value.length;
  }

  if (lastIndex < text.length) {
    segments.push({ text: text.slice(lastIndex), isHit: false });
  }

  return segments.length ? segments : [{ text, isHit: false }];
}

export function formatFragmentSegments(
  text: string,
  query: string,
  stemmer: (word: string) => string,
  maxLength = 65 * 4,
): HighlightSegment[] {
  let formatted = text;
  if (formatted.length > maxLength) {
    const idx = formatted.lastIndexOf(' ', maxLength);
    if (idx >= 0) {
      formatted = formatted.substring(0, idx) + '...';
    }
  }
  return highlightSegments(formatted, query, stemmer);
}

export function segmentsToSafeHtml(segments: HighlightSegment[]): string {
  return segments
    .map((s) => (s.isHit ? `<b>${escapeHtml(s.text)}</b>` : escapeHtml(s.text)))
    .join('');
}

