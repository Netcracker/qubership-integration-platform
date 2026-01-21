// Initialize lunr globally for elasticlunr
// This file must be imported FIRST to ensure lunr is available before elasticlunr loads
import lunr from 'lunr';

// Set lunr in both globalThis and window for maximum compatibility
(globalThis as Record<string, unknown>).lunr = lunr;
if (typeof window !== 'undefined') {
  (window as Record<string, unknown>).lunr = lunr;
}

// Re-export lunr for potential direct usage
export { lunr };
