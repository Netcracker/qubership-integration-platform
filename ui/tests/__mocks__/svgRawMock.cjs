// Stub for `*.svg?raw` imports (Vite raw loader) under Jest.
// Returns a minimal monochrome SVG string so IconProvider's string branch parses
// and recolors it the same way it does the real asset.
module.exports = '<svg viewBox="0 0 24 24"><path stroke="black" d="M0 0h24v24H0z"/></svg>';
